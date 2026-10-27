package com.multiregion.router;

import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpHeaderNames;
import io.servicetalk.http.api.HttpHeaders;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.StreamingHttpResponseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static io.servicetalk.http.api.HttpResponseStatus.BAD_GATEWAY;
import static io.servicetalk.http.api.HttpResponseStatus.GATEWAY_TIMEOUT;
import static io.servicetalk.http.api.HttpResponseStatus.SERVICE_UNAVAILABLE;

/**
 * High-performance router implementing Envoy/ServiceTalk Locality Priority (P0 -> P1)
 * and outlier-aware fast failover for Multi-Region routing.
 */
public final class LocalityPriorityRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalityPriorityRouter.class);

    private static final CharSequence HEADER_SOURCE_REGION = "X-Source-Region";
    private static final CharSequence HEADER_ROUTED_REGION = "X-Routed-Region";
    private static final CharSequence HEADER_ROUTER_BACKEND = "X-Router-Backend";
    private static final CharSequence HEADER_ROUTED_PRIORITY = "X-Routed-Priority";
    private static final CharSequence HEADER_FAILOVER = "X-Failover";

    private final List<RegionEndpoint> endpoints;
    private final int outlierFailureThreshold;
    private final long outlierCooldownMillis;
    private final Duration requestTimeout;

    public LocalityPriorityRouter(RegionEndpoint usEndpoint,
                                  RegionEndpoint euEndpoint,
                                  int outlierFailureThreshold,
                                  long outlierCooldownMillis) {
        this(List.of(usEndpoint, euEndpoint), outlierFailureThreshold, outlierCooldownMillis, Duration.ofMillis(800));
    }

    public LocalityPriorityRouter(RegionEndpoint usEndpoint,
                                  RegionEndpoint euEndpoint,
                                  int outlierFailureThreshold,
                                  long outlierCooldownMillis,
                                  Duration requestTimeout) {
        this(List.of(usEndpoint, euEndpoint), outlierFailureThreshold, outlierCooldownMillis, requestTimeout);
    }

    public LocalityPriorityRouter(List<RegionEndpoint> endpoints,
                                  int outlierFailureThreshold,
                                  long outlierCooldownMillis,
                                  Duration requestTimeout) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints");
        }
        this.endpoints = List.copyOf(endpoints);
        this.outlierFailureThreshold = outlierFailureThreshold;
        this.outlierCooldownMillis = outlierCooldownMillis;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    public Single<StreamingHttpResponse> route(StreamingHttpRequest streamingRequest, StreamingHttpResponseFactory responseFactory) {
        return streamingRequest.toRequest().flatMap(request -> {
            final CharSequence sourceRegionHeader = request.headers().get(HEADER_SOURCE_REGION);
            final String sourceRegion = sourceRegionHeader != null ? sourceRegionHeader.toString() : "us-east-1";
            List<RegionEndpoint> ordered = RegionOrder.forSource(sourceRegion, endpoints);
            return dispatchInOrder(ordered, 0, request, responseFactory);
        });
    }

    private Single<StreamingHttpResponse> dispatchInOrder(List<RegionEndpoint> ordered,
                                                          int index,
                                                          HttpRequest request,
                                                          StreamingHttpResponseFactory responseFactory) {
        if (index >= ordered.size()) {
            StreamingHttpResponse errorResponse = responseFactory.serviceUnavailable();
            errorResponse.headers().set(HEADER_ROUTER_BACKEND, "servicetalk");
            return Single.succeeded(errorResponse);
        }
        RegionEndpoint endpoint = ordered.get(index);
        if (!endpoint.isHealthy()) {
            return dispatchInOrder(ordered, index + 1, request, responseFactory);
        }
        String priority = "P" + index;
        boolean failover = index > 0;
        return dispatchToEndpoint(endpoint, request)
                .flatMap(response -> {
                    if (isServerFailure(response.status())) {
                        endpoint.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                        LOGGER.warn("Endpoint {} returned {}, trying the next region",
                                endpoint.regionName(), response.status());
                        return dispatchInOrder(ordered, index + 1, request, responseFactory);
                    }
                    endpoint.recordSuccess();
                    return Single.succeeded(applyMetadataHeaders(response, endpoint.regionName(), priority, failover));
                })
                .onErrorResume(error -> {
                    endpoint.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                    LOGGER.warn("Endpoint {} failed ({}), trying the next region",
                            endpoint.regionName(), error.getMessage());
                    return dispatchInOrder(ordered, index + 1, request, responseFactory);
                });
    }

    private Single<StreamingHttpResponse> dispatchToEndpoint(RegionEndpoint endpoint, HttpRequest request) {
        HttpRequest forwardRequest = endpoint.httpClient().newRequest(request.method(), request.requestTarget());
        forwardRequest.headers().set(request.headers());
        forwardRequest.headers().set(HttpHeaderNames.HOST, endpoint.host() + ":" + endpoint.port());
        forwardRequest.payloadBody(request.payloadBody().duplicate());
        return endpoint.httpClient().request(forwardRequest)
                .timeout(requestTimeout)
                .map(HttpResponse::toStreamingResponse);
    }

    private static boolean isServerFailure(HttpResponseStatus status) {
        return status.equals(BAD_GATEWAY) ||
                status.equals(SERVICE_UNAVAILABLE) ||
                status.equals(GATEWAY_TIMEOUT);
    }

    private static StreamingHttpResponse applyMetadataHeaders(StreamingHttpResponse response,
                                                               String routedRegion,
                                                               String priority,
                                                               boolean failover) {
        HttpHeaders headers = response.headers();
        headers.set(HEADER_ROUTED_REGION, routedRegion);
        headers.set(HEADER_ROUTER_BACKEND, "servicetalk");
        headers.set(HEADER_ROUTED_PRIORITY, priority);
        headers.set(HEADER_FAILOVER, String.valueOf(failover));
        return response;
    }
}
