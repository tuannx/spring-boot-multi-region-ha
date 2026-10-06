package com.multiregion.router;

import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.netty.HttpClients;
import io.servicetalk.http.netty.HttpServers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.servicetalk.http.api.HttpHeaderNames.CONTENT_TYPE;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static io.servicetalk.http.api.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceTalkMultiRegionRouterTest {

    private HttpServerContext mockServerUs;
    private HttpServerContext mockServerEu;
    private HttpServerContext routerServer;

    private StreamingHttpClient clientUs;
    private StreamingHttpClient clientEu;
    private StreamingHttpClient routerClient;

    private final AtomicBoolean usHealthy = new AtomicBoolean(true);

    @BeforeEach
    void setUp() throws Exception {
        usHealthy.set(true);

        mockServerUs = HttpServers.forPort(0)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> {
                    if (usHealthy.get()) {
                        return request.toRequest().map(req -> {
                            StreamingHttpResponse resp = responseFactory.ok();
                            resp.headers().set(CONTENT_TYPE, "text/plain");
                            resp.payloadBody(Publisher.from(ctx.executionContext().bufferAllocator().fromUtf8(req.payloadBody().toString(StandardCharsets.UTF_8))));
                            return resp;
                        });
                    } else {
                        StreamingHttpResponse resp = responseFactory.serviceUnavailable();
                        return ctx.executionContext().executor().submit(() -> resp);
                    }
                });

        mockServerEu = HttpServers.forPort(0)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> {
                    return request.toRequest().map(req -> {
                        StreamingHttpResponse resp = responseFactory.ok();
                        resp.headers().set(CONTENT_TYPE, "text/plain");
                        resp.payloadBody(Publisher.from(ctx.executionContext().bufferAllocator().fromUtf8(req.payloadBody().toString(StandardCharsets.UTF_8))));
                        return resp;
                    });
                });

        int usPort = ((InetSocketAddress) mockServerUs.listenAddress()).getPort();
        int euPort = ((InetSocketAddress) mockServerEu.listenAddress()).getPort();

        clientUs = HttpClients.forSingleAddress("127.0.0.1", usPort).buildStreaming();
        clientEu = HttpClients.forSingleAddress("127.0.0.1", euPort).buildStreaming();

        RegionEndpoint usEndpoint = new RegionEndpoint("us-east-1", "127.0.0.1", usPort, 0, clientUs);
        RegionEndpoint euEndpoint = new RegionEndpoint("eu-west-1", "127.0.0.1", euPort, 1, clientEu);

        // Failure threshold: 2, Cooldown: 1000ms
        LocalityPriorityRouter router = new LocalityPriorityRouter(usEndpoint, euEndpoint, 2, 1000L);

        routerServer = HttpServers.forPort(0)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> router.route(request, responseFactory));

        int routerPort = ((InetSocketAddress) routerServer.listenAddress()).getPort();
        routerClient = HttpClients.forSingleAddress("127.0.0.1", routerPort).buildStreaming();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (routerClient != null) routerClient.closeGracefully();
        if (routerServer != null) routerServer.closeGracefully();
        if (clientUs != null) clientUs.closeGracefully();
        if (clientEu != null) clientEu.closeGracefully();
        if (mockServerUs != null) mockServerUs.closeGracefully();
        if (mockServerEu != null) mockServerEu.closeGracefully();
    }

    @Test
    void normalRoutingToPrimaryUS() throws Exception {
        StreamingHttpRequest request = routerClient.get("/api/products");
        StreamingHttpResponse response = routerClient.request(request).toFuture().get();

        assertEquals(OK, response.status());
        assertEquals("us-east-1", response.headers().get("X-Routed-Region").toString());
        assertEquals("P0", response.headers().get("X-Routed-Priority").toString());
        assertEquals("false", response.headers().get("X-Failover").toString());
        assertEquals("servicetalk", response.headers().get("X-Router-Backend").toString());
    }

    @Test
    void explicitRoutingToPrimaryEU() throws Exception {
        StreamingHttpRequest request = routerClient.get("/api/products");
        request.headers().set("X-Source-Region", "eu-west-1");
        StreamingHttpResponse response = routerClient.request(request).toFuture().get();

        assertEquals(OK, response.status());
        assertEquals("eu-west-1", response.headers().get("X-Routed-Region").toString());
        assertEquals("P0", response.headers().get("X-Routed-Priority").toString());
        assertEquals("false", response.headers().get("X-Failover").toString());
    }

    @Test
    void instantFailoverToSecondaryWhenPrimaryFails() throws Exception {
        // Break US primary
        usHealthy.set(false);

        StreamingHttpRequest request = routerClient.get("/api/products");
        StreamingHttpResponse response = routerClient.request(request).toFuture().get();

        // Must succeed via EU failover!
        assertEquals(OK, response.status());
        assertEquals("eu-west-1", response.headers().get("X-Routed-Region").toString());
        assertEquals("P1", response.headers().get("X-Routed-Priority").toString());
        assertEquals("true", response.headers().get("X-Failover").toString());
    }

    @Test
    void outlierDetectionEjectionRoutesDirectlyWithoutPenalty() throws Exception {
        // Break US primary and trigger failure threshold (2)
        usHealthy.set(false);

        // 1st request -> fails US, failovers to EU
        routerClient.request(routerClient.get("/api/products")).toFuture().get();

        // 2nd request -> fails US, marks US as outlier, failovers to EU
        routerClient.request(routerClient.get("/api/products")).toFuture().get();

        // 3rd request -> US is active outlier, routes directly to EU without attempting US!
        StreamingHttpResponse response = routerClient.request(routerClient.get("/api/products")).toFuture().get();
        assertEquals(OK, response.status());
        assertEquals("eu-west-1", response.headers().get("X-Routed-Region").toString());
        assertEquals("P1", response.headers().get("X-Routed-Priority").toString());
    }

    @Test
    void postRequestBodyPreservedDuringFailover() throws Exception {
        usHealthy.set(false);
        String body = "{\"name\":\"item1\",\"price\":42.0}";
        StreamingHttpRequest request = routerClient.post("/api/products");
        request.payloadBody(Publisher.from(routerClient.executionContext().bufferAllocator().fromUtf8(body)));
        StreamingHttpResponse response = routerClient.request(request).toFuture().get();

        assertEquals(OK, response.status());
        assertEquals("eu-west-1", response.headers().get("X-Routed-Region").toString());
        assertEquals("true", response.headers().get("X-Failover").toString());
        String responseBody = response.payloadBody().collect(StringBuilder::new, (sb, b) -> sb.append(b.toString(StandardCharsets.UTF_8))).toFuture().get().toString();
        assertEquals(body, responseBody);
    }
}
