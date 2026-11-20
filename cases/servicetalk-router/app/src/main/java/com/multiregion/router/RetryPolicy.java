package com.multiregion.router;

import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.transport.api.RetryableException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Set;

import static io.servicetalk.http.api.HttpResponseStatus.BAD_GATEWAY;
import static io.servicetalk.http.api.HttpResponseStatus.GATEWAY_TIMEOUT;
import static io.servicetalk.http.api.HttpResponseStatus.SERVICE_UNAVAILABLE;

/**
 * Decides whether a failed attempt may be sent to the next region.
 *
 * <p>A request that never left the router (connect refused, DNS failure, no host to send to) is safe
 * to send again whatever its method. Once a request may have reached the backend, only idempotent
 * methods (RFC 9110 section 9.2.2) are sent again, because replaying a POST or PATCH can apply the
 * write twice. This follows Gateway API GEP-1731 and NGINX, which never pass POST, LOCK or PATCH
 * to the next upstream.
 */
final class RetryPolicy {

    private static final int MAX_CAUSE_DEPTH = 32;
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE");

    private RetryPolicy() {
    }

    static boolean isIdempotent(HttpRequestMethod method) {
        return IDEMPOTENT.contains(method.name());
    }

    /** True when the error shows the request was never written to the network. */
    static boolean failedBeforeSend(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof RetryableException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    static boolean isRetriableStatus(HttpResponseStatus status) {
        return status.equals(BAD_GATEWAY) || status.equals(SERVICE_UNAVAILABLE) || status.equals(GATEWAY_TIMEOUT);
    }

    static boolean mayRetryAfterError(HttpRequestMethod method, Throwable error) {
        return failedBeforeSend(error) || isIdempotent(method);
    }

    /** A 502/503/504 came from the backend, so the request reached it: only idempotent methods go again. */
    static boolean mayRetryAfterResponse(HttpRequestMethod method, HttpResponseStatus status) {
        return isRetriableStatus(status) && isIdempotent(method);
    }
}
