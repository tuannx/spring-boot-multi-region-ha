package com.multiregion.router;

import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.netty.HttpClients;
import io.servicetalk.http.netty.HttpServers;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Puts a router in front of mock regions and gives tests a client for it. */
final class RouterHarness implements AutoCloseable {

    /** One answer from the router and how long it took. */
    record Result(HttpResponse response, long millis) {
        String header(CharSequence name) {
            CharSequence value = response.headers().get(name);
            return value == null ? null : value.toString();
        }

        int status() {
            return response.status().code();
        }
    }

    final LocalityPriorityRouter router;
    private final HttpServerContext server;
    private final HttpClient client;

    RouterHarness(LocalityPriorityRouter router) throws Exception {
        this.router = router;
        this.server = HttpServers.forPort(0).listenStreamingAndAwait((ctx, request, factory) -> router.route(request, factory));
        this.client = HttpClients.forSingleAddress("127.0.0.1", ((InetSocketAddress) server.listenAddress()).getPort()).build();
    }

    HttpRequest get(String path) {
        return client.get(path);
    }

    HttpRequest post(String path, String body) {
        return client.post(path).payloadBody(client.executionContext().bufferAllocator().fromUtf8(body));
    }

    Result send(HttpRequest request) throws Exception {
        long start = System.nanoTime();
        HttpResponse response = client.request(request).toFuture().get(10, TimeUnit.SECONDS);
        return new Result(response, (System.nanoTime() - start) / 1_000_000L);
    }

    @Override
    public void close() throws Exception {
        client.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
        server.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
    }
}
