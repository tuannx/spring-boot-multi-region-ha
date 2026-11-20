package com.multiregion.router;

import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import io.servicetalk.http.netty.HttpServers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A fake regional backend whose behaviour a test can switch at any time. */
final class MockRegion implements AutoCloseable {

    enum Mode {
        /** Answers 200 (201 for POST) and echoes the body. */
        OK,
        /** Never answers, health probes included: a hung region. */
        HANG,
        /** Counts the write, then answers after {@link #slowMillis}: the write landed but the answer is late. */
        SLOW_COMMIT,
        /** Answers 503 to everything. */
        UNAVAILABLE
    }

    final String region;
    final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    volatile long slowMillis = 1200;

    private final HttpServerContext server;
    private final StreamingHttpClient client;

    MockRegion(String region) throws Exception {
        this.region = region;
        this.server = HttpServers.forPort(0).listenAndAwait((ctx, request, factory) -> {
            boolean probe = request.requestTarget().startsWith("/actuator/health");
            if (!probe) {
                requests.incrementAndGet();
            }
            boolean write = "POST".equals(request.method().name());
            switch (mode.get()) {
                case HANG:
                    return Single.never();
                case UNAVAILABLE:
                    return Single.succeeded(factory.serviceUnavailable());
                case SLOW_COMMIT:
                    if (write && !probe) {
                        writes.incrementAndGet();
                        return ctx.executionContext().executor()
                                .timer(slowMillis, TimeUnit.MILLISECONDS)
                                .toSingle()
                                .map(ignored -> factory.newResponse(HttpResponseStatus.CREATED));
                    }
                    return Single.succeeded(factory.ok());
                default:
                    if (write && !probe) {
                        writes.incrementAndGet();
                    }
                    String body = request.payloadBody().toString(StandardCharsets.UTF_8);
                    return Single.succeeded((write ? factory.newResponse(HttpResponseStatus.CREATED) : factory.ok())
                            .payloadBody(body, io.servicetalk.http.api.HttpSerializers.textSerializerUtf8()));
            }
        });
        this.client = HttpClients.forSingleAddress("127.0.0.1", port()).buildStreaming();
    }

    int port() {
        return ((InetSocketAddress) server.listenAddress()).getPort();
    }

    RegionEndpoint endpoint(int priority) {
        return new RegionEndpoint(region, "127.0.0.1", port(), priority, client);
    }

    @Override
    public void close() throws Exception {
        client.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
        server.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
    }
}
