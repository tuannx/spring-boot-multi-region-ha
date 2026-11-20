package com.multiregion.router;

import io.servicetalk.client.api.NoActiveHostException;
import io.servicetalk.client.api.RetryableConnectException;
import io.servicetalk.http.api.HttpRequestMethod;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static io.servicetalk.http.api.HttpRequestMethod.DELETE;
import static io.servicetalk.http.api.HttpRequestMethod.GET;
import static io.servicetalk.http.api.HttpRequestMethod.PATCH;
import static io.servicetalk.http.api.HttpRequestMethod.POST;
import static io.servicetalk.http.api.HttpRequestMethod.PUT;
import static io.servicetalk.http.api.HttpResponseStatus.BAD_REQUEST;
import static io.servicetalk.http.api.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void idempotentMethodsFollowRfc9110() {
        for (HttpRequestMethod method : new HttpRequestMethod[] {GET, PUT, DELETE}) {
            assertTrue(RetryPolicy.isIdempotent(method), method.name());
        }
        assertFalse(RetryPolicy.isIdempotent(POST));
        assertFalse(RetryPolicy.isIdempotent(PATCH));
    }

    @Test
    void errorsBeforeTheRequestIsWrittenAreSafeForAnyMethod() {
        Throwable refused = new ExecutionException(new RetryableConnectException("refused"));
        Throwable dns = new ExecutionException(new UnknownHostException("app-us"));
        Throwable noHost = new NoActiveHostException("no active host");

        assertTrue(RetryPolicy.mayRetryAfterError(POST, refused));
        assertTrue(RetryPolicy.mayRetryAfterError(POST, dns));
        assertTrue(RetryPolicy.mayRetryAfterError(PATCH, noHost));
    }

    @Test
    void errorsAfterTheRequestMayHaveLandedAreRetriedOnlyForIdempotentMethods() {
        Throwable timeout = new TimeoutException();
        Throwable reset = new IOException("Connection reset by peer");

        assertFalse(RetryPolicy.mayRetryAfterError(POST, timeout));
        assertFalse(RetryPolicy.mayRetryAfterError(POST, reset));
        assertTrue(RetryPolicy.mayRetryAfterError(GET, timeout));
        assertTrue(RetryPolicy.mayRetryAfterError(PUT, reset));
    }

    @Test
    void gatewayErrorsFromTheBackendAreRetriedOnlyForIdempotentMethods() {
        assertTrue(RetryPolicy.mayRetryAfterResponse(GET, SERVICE_UNAVAILABLE));
        assertFalse(RetryPolicy.mayRetryAfterResponse(POST, SERVICE_UNAVAILABLE));
        assertFalse(RetryPolicy.mayRetryAfterResponse(GET, BAD_REQUEST));
    }
}
