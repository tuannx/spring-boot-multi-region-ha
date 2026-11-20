package com.multiregion.router;

import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpHeaderNames;
import io.servicetalk.http.api.HttpHeaders;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.StreamingHttpResponseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes each request to the nearest healthy region (P0, then P1, ...) and fails over the way
 * Envoy does:
 *
 * <ul>
 *   <li>regions that are unhealthy (probes failing or ejected) are skipped without a try;</li>
 *   <li>a failed try goes to the next region only when {@link RetryPolicy} says it is safe, so a
 *       POST that may have been written is never replayed in a second region;</li>
 *   <li>retries are capped by a {@link RetryBudget};</li>
 *   <li>each try has its own timeout and the whole request has a route timeout;</li>
 *   <li>when no region is healthy the router tries them all anyway (panic mode) instead of
 *       refusing every request.</li>
 * </ul>
 */
public final class LocalityPriorityRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalityPriorityRouter.class);

    static final CharSequence HEADER_SOURCE_REGION = "X-Source-Region";
    static final CharSequence HEADER_ROUTED_REGION = "X-Routed-Region";
    static final CharSequence HEADER_ROUTER_BACKEND = "X-Router-Backend";
    static final CharSequence HEADER_ROUTED_PRIORITY = "X-Routed-Priority";
    static final CharSequence HEADER_FAILOVER = "X-Failover";
    static final CharSequence HEADER_ATTEMPTS = "X-Router-Attempts";
    static final CharSequence HEADER_RETRY_SKIPPED = "X-Retry-Skipped";
    static final CharSequence HEADER_PANIC = "X-Router-Panic";
    static final CharSequence HEADER_TIMEOUT = "X-Router-Timeout";

    static final String SKIPPED_UNSAFE_METHOD = "unsafe-method";
    static final String SKIPPED_BUDGET = "retry-budget";
    static final String SKIPPED_NO_REGION = "no-more-regions";

    private final List<RegionEndpoint> endpoints;
    private final RouterPolicy policy;
    private final RetryBudget budget;

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
        this(endpoints, RouterPolicy.legacy(outlierFailureThreshold, outlierCooldownMillis,
                Objects.requireNonNull(requestTimeout, "requestTimeout"), endpoints == null ? 1 : endpoints.size()));
    }

    public LocalityPriorityRouter(List<RegionEndpoint> endpoints, RouterPolicy policy) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints");
        }
        this.endpoints = List.copyOf(endpoints);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.budget = new RetryBudget(policy.retryBudgetPercent(), policy.minRetryConcurrency());
    }

    RetryBudget retryBudget() {
        return budget;
    }

    public Single<StreamingHttpResponse> route(StreamingHttpRequest streamingRequest,
                                               StreamingHttpResponseFactory responseFactory) {
        return Single.defer(() -> {
            Route route = new Route(responseFactory);
            budget.requestStarted();
            return streamingRequest.toRequest()
                    .flatMap(request -> {
                        CharSequence sourceHeader = request.headers().get(HEADER_SOURCE_REGION);
                        String sourceRegion = sourceHeader != null ? sourceHeader.toString() : "us-east-1";
                        List<RegionEndpoint> ordered = RegionOrder.forSource(sourceRegion, endpoints);
                        route.panic = ordered.stream().noneMatch(RegionEndpoint::isHealthy);
                        if (route.panic) {
                            LOGGER.warn("No healthy region, panic mode: trying all regions");
                        }
                        return attempt(route, ordered, nextCandidate(route, ordered, 0), request);
                    })
                    .timeout(policy.routeTimeout())
                    .onErrorResume(error -> error instanceof TimeoutException
                            ? Single.succeeded(route.routeTimedOut())
                            : Single.failed(error))
                    .afterFinally(() -> {
                        budget.releaseRetries(route.retriesHeld.get());
                        budget.requestFinished();
                    });
        });
    }

    private Single<StreamingHttpResponse> attempt(Route route, List<RegionEndpoint> ordered, int index,
                                                  HttpRequest request) {
        if (index < 0) {
            return Single.succeeded(route.finish(route.factory.serviceUnavailable(), null, -1, null));
        }
        RegionEndpoint endpoint = ordered.get(index);
        route.attempts++;
        return dispatch(endpoint, request).flatMap(response -> {
            if (!RetryPolicy.isRetriableStatus(response.status())) {
                endpoint.recordSuccess(policy.baseEjection().toMillis());
                return Single.succeeded(route.finish(response, endpoint, index, null));
            }
            recordFailure(endpoint, response.status().toString());
            boolean allowed = RetryPolicy.mayRetryAfterResponse(request.method(), response.status());
            return retryOrFinish(route, ordered, index, request, allowed, response, endpoint);
        }).onErrorResume(error -> {
            recordFailure(endpoint, error.toString());
            boolean allowed = RetryPolicy.mayRetryAfterError(request.method(), error);
            StreamingHttpResponse failure = error instanceof TimeoutException
                    ? route.factory.gatewayTimeout() : route.factory.badGateway();
            return retryOrFinish(route, ordered, index, request, allowed, failure, endpoint);
        });
    }

    private Single<StreamingHttpResponse> retryOrFinish(Route route, List<RegionEndpoint> ordered, int index,
                                                        HttpRequest request, boolean allowed,
                                                        StreamingHttpResponse failure, RegionEndpoint endpoint) {
        int next = nextCandidate(route, ordered, index + 1);
        String skipped = next < 0 ? SKIPPED_NO_REGION
                : !allowed ? SKIPPED_UNSAFE_METHOD
                : !budget.tryAcquireRetry() ? SKIPPED_BUDGET
                : null;
        if (skipped != null) {
            LOGGER.warn("{} {} failed in {}, not retrying: {}",
                    request.method(), request.requestTarget(), endpoint.regionName(), skipped);
            return Single.succeeded(route.finish(failure, endpoint, index, skipped));
        }
        route.retriesHeld.incrementAndGet();
        return attempt(route, ordered, next, request);
    }

    private void recordFailure(RegionEndpoint endpoint, String reason) {
        boolean ejected = endpoint.recordFailure(policy.failureThreshold(),
                policy.baseEjection().toMillis(), policy.maxEjection().toMillis());
        if (ejected) {
            LOGGER.warn("Outlier detection: ejected {} after {} ({} ejections)",
                    endpoint.regionName(), reason, endpoint.ejectionCount());
        }
    }

    private static int nextCandidate(Route route, List<RegionEndpoint> ordered, int from) {
        for (int i = from; i < ordered.size(); i++) {
            if (route.panic || ordered.get(i).isHealthy()) {
                return i;
            }
        }
        return -1;
    }

    private Single<StreamingHttpResponse> dispatch(RegionEndpoint endpoint, HttpRequest request) {
        HttpRequest forwardRequest = endpoint.httpClient().newRequest(request.method(), request.requestTarget());
        forwardRequest.headers().set(request.headers());
        forwardRequest.headers().set(HttpHeaderNames.HOST, endpoint.host() + ":" + endpoint.port());
        forwardRequest.payloadBody(request.payloadBody().duplicate());
        return endpoint.httpClient().request(forwardRequest)
                .timeout(policy.perTryTimeout())
                .map(HttpResponse::toStreamingResponse);
    }

    /** State of one client request across its tries. */
    private static final class Route {
        final StreamingHttpResponseFactory factory;
        final AtomicInteger retriesHeld = new AtomicInteger();
        volatile boolean panic;
        volatile int attempts;

        Route(StreamingHttpResponseFactory factory) {
            this.factory = factory;
        }

        StreamingHttpResponse finish(StreamingHttpResponse response, RegionEndpoint endpoint, int index,
                                     String retrySkipped) {
            HttpHeaders headers = response.headers();
            headers.set(HEADER_ROUTER_BACKEND, "servicetalk");
            headers.set(HEADER_ATTEMPTS, String.valueOf(attempts));
            if (endpoint != null) {
                headers.set(HEADER_ROUTED_REGION, endpoint.regionName());
                headers.set(HEADER_ROUTED_PRIORITY, "P" + index);
                headers.set(HEADER_FAILOVER, String.valueOf(index > 0));
            }
            if (retrySkipped != null) {
                headers.set(HEADER_RETRY_SKIPPED, retrySkipped);
            }
            if (panic) {
                headers.set(HEADER_PANIC, "true");
            }
            return response;
        }

        StreamingHttpResponse routeTimedOut() {
            StreamingHttpResponse response = finish(factory.gatewayTimeout(), null, -1, null);
            response.headers().set(HEADER_TIMEOUT, "route");
            return response;
        }
    }
}
