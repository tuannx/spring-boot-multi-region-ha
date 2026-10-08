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

    private final RegionEndpoint usEndpoint;
    private final RegionEndpoint euEndpoint;
    private final int outlierFailureThreshold;
    private final long outlierCooldownMillis;
    private final Duration requestTimeout;

    public LocalityPriorityRouter(RegionEndpoint usEndpoint,
                                  RegionEndpoint euEndpoint,
                                  int outlierFailureThreshold,
                                  long outlierCooldownMillis) {
        this(usEndpoint, euEndpoint, outlierFailureThreshold, outlierCooldownMillis, Duration.ofMillis(800));
    }

    public LocalityPriorityRouter(RegionEndpoint usEndpoint,
                                  RegionEndpoint euEndpoint,
                                  int outlierFailureThreshold,
                                  long outlierCooldownMillis,
                                  Duration requestTimeout) {
        this.usEndpoint = Objects.requireNonNull(usEndpoint, "usEndpoint");
        this.euEndpoint = Objects.requireNonNull(euEndpoint, "euEndpoint");
        this.outlierFailureThreshold = outlierFailureThreshold;
        this.outlierCooldownMillis = outlierCooldownMillis;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    public Single<StreamingHttpResponse> route(StreamingHttpRequest streamingRequest, StreamingHttpResponseFactory responseFactory) {
        return streamingRequest.toRequest().flatMap(request -> {
            final CharSequence sourceRegionHeader = request.headers().get(HEADER_SOURCE_REGION);
            final String sourceRegion = sourceRegionHeader != null ? sourceRegionHeader.toString() : "us-east-1";

            // Determine P0 (Primary) and P1 (Secondary) based on requested source region
            final RegionEndpoint primary;
            final RegionEndpoint secondary;
            if ("eu-west-1".equalsIgnoreCase(sourceRegion)) {
                primary = euEndpoint;
                secondary = usEndpoint;
            } else {
                primary = usEndpoint;
                secondary = euEndpoint;
            }

            if (primary.isHealthy()) {
                return dispatchToEndpoint(primary, request)
                        .flatMap(response -> {
                            if (isServerFailure(response.status())) {
                                primary.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                                LOGGER.warn("Primary endpoint {} returned {}, fast-failing over to secondary {}",
                                        primary.regionName(), response.status(), secondary.regionName());
                                return dispatchToEndpoint(secondary, request)
                                        .map(failoverResponse -> applyMetadataHeaders(failoverResponse, secondary.regionName(), "P1", true));
                            }
                            primary.recordSuccess();
                            return Single.succeeded(applyMetadataHeaders(response, primary.regionName(), "P0", false));
                        })
                        .onErrorResume(error -> {
                            primary.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                            LOGGER.warn("Primary endpoint {} failed ({}), immediately failing over to secondary {}",
                                    primary.regionName(), error.getMessage(), secondary.regionName());
                            return dispatchToEndpoint(secondary, request)
                                    .map(failoverResponse -> applyMetadataHeaders(failoverResponse, secondary.regionName(), "P1", true))
                                    .onErrorResume(secondError -> {
                                        secondary.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                                        LOGGER.error("Both primary ({}) and secondary ({}) endpoints failed!",
                                                primary.regionName(), secondary.regionName(), secondError);
                                        StreamingHttpResponse errorResponse = responseFactory.serviceUnavailable();
                                        errorResponse.headers().set(HEADER_ROUTER_BACKEND, "servicetalk");
                                        return Single.succeeded(errorResponse);
                                    });
                        });
            } else {
                // Primary is already known to be an outlier: route directly to secondary with zero latency penalty
                LOGGER.info("Primary endpoint {} is an active outlier; routing directly to secondary {}",
                        primary.regionName(), secondary.regionName());
                return dispatchToEndpoint(secondary, request)
                        .map(response -> {
                            secondary.recordSuccess();
                            return applyMetadataHeaders(response, secondary.regionName(), "P1", true);
                        })
                        .onErrorResume(error -> {
                            secondary.recordFailure(outlierFailureThreshold, outlierCooldownMillis);
                            LOGGER.error("Secondary endpoint {} also failed while primary was an outlier!", secondary.regionName(), error);
                            StreamingHttpResponse errorResponse = responseFactory.serviceUnavailable();
                            errorResponse.headers().set(HEADER_ROUTER_BACKEND, "servicetalk");
                            return Single.succeeded(errorResponse);
                        });
            }
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
