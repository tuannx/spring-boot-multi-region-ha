package com.multiregion.router;

import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionOrderTest {

    private final List<StreamingHttpClient> clients = new ArrayList<>();
    private List<RegionEndpoint> endpoints;

    @BeforeEach
    void setUp() {
        endpoints = List.of(
                endpoint("us-east-1", 0),
                endpoint("eu-west-1", 1),
                endpoint("ap-southeast-1", 2),
                endpoint("ap-northeast-1", 3));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (StreamingHttpClient client : clients) {
            client.closeGracefully();
        }
    }

    @Test
    void eachRegionPrefersItselfThenItsPair() {
        assertEquals(List.of("us-east-1", "eu-west-1", "ap-southeast-1", "ap-northeast-1"), names("us-east-1"));
        assertEquals(List.of("eu-west-1", "us-east-1", "ap-southeast-1", "ap-northeast-1"), names("eu-west-1"));
        assertEquals(List.of("ap-southeast-1", "ap-northeast-1", "us-east-1", "eu-west-1"), names("ap-southeast-1"));
        assertEquals(List.of("ap-northeast-1", "ap-southeast-1", "us-east-1", "eu-west-1"), names("AP-NORTHEAST-1"));
    }

    @Test
    void unknownSourceStaysOnTheUsEndpointWhenThatEndpointExists() {
        assertEquals("us-east-1", names("ap-south-1").get(0));
    }

    @Test
    void twoRegionRouterKeepsUsThenEu() {
        List<RegionEndpoint> pair = endpoints.subList(0, 2);
        assertEquals(
                List.of("eu-west-1", "us-east-1"),
                RegionOrder.forSource("eu-west-1", pair).stream().map(RegionEndpoint::regionName).toList());
    }

    @Test
    void optionalAsiaPacificEndpointsAreAbsentUntilConfigured() {
        assertEquals(
                List.of("us-east-1", "eu-west-1"),
                RouterEndpoints.specs(Map.of()).stream().map(RouterEndpoints.EndpointSpec::region).toList());
        assertEquals(
                List.of("us-east-1", "eu-west-1", "ap-southeast-1", "ap-northeast-1"),
                RouterEndpoints.specs(Map.of(
                        "APSE1_ENDPOINT_HOST", "app-apse1",
                        "APNE1_ENDPOINT_HOST", "app-apne1"))
                        .stream()
                        .map(RouterEndpoints.EndpointSpec::region)
                        .toList());
    }

    @Test
    void legacyTargetVariablesFillTheUsAndEuHosts() {
        Map<String, String> environment = ServiceTalkRouterApplication.routerEnvironment(Map.of(
                "TARGET_US_HOST", "legacy-us",
                "TARGET_EU_HOST", "legacy-eu"));
        List<String> hosts = RouterEndpoints.specs(environment).stream()
                .map(RouterEndpoints.EndpointSpec::host)
                .toList();
        assertEquals(List.of("legacy-us", "legacy-eu"), hosts);
    }

    private List<String> names(String source) {
        return RegionOrder.forSource(source, endpoints).stream().map(RegionEndpoint::regionName).toList();
    }

    private RegionEndpoint endpoint(String region, int priority) {
        StreamingHttpClient client = HttpClients.forSingleAddress("127.0.0.1", 9).buildStreaming();
        clients.add(client);
        return new RegionEndpoint(region, "127.0.0.1", 9, priority, client);
    }
}
