package com.multiregion.xds;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {

    private XdsControlPlane controlPlane;
    private AdminServer admin;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void setUp() throws Exception {
        controlPlane = new XdsControlPlane(0, new Topology(List.of(
                new RegionState("us-east-1", "127.0.0.1", 8080, 0, 100, true),
                new RegionState("eu-west-1", "127.0.0.1", 8081, 1, 100, true)))).start();
        admin = new AdminServer(0, controlPlane).start();
    }

    @AfterEach
    void tearDown() throws Exception {
        admin.close();
        controlPlane.close();
    }

    private HttpResponse<String> post(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + pathAndQuery))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void drainRestoreAndWeightsPublishNewVersions() throws Exception {
        HttpResponse<String> drained = post("/drain?region=us-east-1");
        assertEquals(200, drained.statusCode());
        assertTrue(drained.body().startsWith("v2"));
        assertFalse(controlPlane.topology().region("us-east-1").healthy());

        post("/restore?region=us-east-1");
        assertTrue(controlPlane.topology().region("us-east-1").healthy());

        post("/weights?us-east-1=90&eu-west-1=10");
        assertEquals(90, controlPlane.topology().region("us-east-1").weight());
        assertEquals(0, controlPlane.topology().region("eu-west-1").priority());

        HttpResponse<String> reset = post("/reset");
        assertTrue(reset.body().startsWith("v5"));
        assertEquals(1, controlPlane.topology().region("eu-west-1").priority());
    }

    @Test
    void badInputIsRejectedWithoutPublishing() throws Exception {
        assertEquals(400, post("/drain?region=mars-1").statusCode());
        assertEquals(400, post("/drain").statusCode());
        assertTrue(controlPlane.topology().region("us-east-1").healthy());
    }
}
