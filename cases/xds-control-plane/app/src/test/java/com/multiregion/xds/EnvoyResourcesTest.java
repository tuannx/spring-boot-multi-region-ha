package com.multiregion.xds;

import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Envoy side cannot run here, so these pin the config that the compose demo relies on. */
class EnvoyResourcesTest {

    private final Topology topology = new Topology(List.of(
            new RegionState("us-east-1", "127.0.0.1", 8080, 0, 100, true),
            new RegionState("eu-west-1", "127.0.0.1", 8081, 1, 100, false)));

    @Test
    void snapshotIsConsistent() throws Exception {
        Snapshot snapshot = EnvoyResources.snapshot(topology, "v1");

        snapshot.ensureConsistent();
        assertEquals(1, snapshot.listeners().resources().size());
        assertEquals(1, snapshot.clusters().resources().size());
    }

    @Test
    void writesAreRetriedOnlyWhenTheConnectionFailed() {
        Route unsafe = EnvoyResources.unsafeRoute();
        Route idempotent = EnvoyResources.idempotentRoute();

        assertEquals("connect-failure", unsafe.getRoute().getRetryPolicy().getRetryOn());
        assertTrue(idempotent.getRoute().getRetryPolicy().getRetryOn().contains("gateway-error"));
        assertEquals(EnvoyResources.IDEMPOTENT_METHODS,
                idempotent.getMatch().getHeaders(0).getStringMatch().getSafeRegex().getRegex());
        assertFalse(EnvoyResources.IDEMPOTENT_METHODS.contains("POST"));
    }

    @Test
    void idempotentRouteIsMatchedBeforeTheCatchAll() throws Exception {
        HttpConnectionManager manager = EnvoyResources.listener().getFilterChains(0).getFilters(0)
                .getTypedConfig().unpack(HttpConnectionManager.class);
        List<Route> routes = manager.getRouteConfig().getVirtualHosts(0).getRoutesList();

        // Envoy takes the first match, so the catch-all for writes must come last.
        assertEquals(List.of("idempotent", "non-idempotent"), routes.stream().map(Route::getName).toList());
    }

    @Test
    void clusterCarriesTheSameNumbersAsTheServiceTalkRouter() {
        Cluster cluster = EnvoyResources.cluster();

        assertEquals(3, cluster.getOutlierDetection().getConsecutive5Xx().getValue());
        assertEquals(5, cluster.getOutlierDetection().getBaseEjectionTime().getSeconds());
        assertEquals(300, cluster.getOutlierDetection().getMaxEjectionTime().getSeconds());
        assertEquals("/actuator/health", cluster.getHealthChecks(0).getHttpHealthCheck().getPath());
        assertEquals(20.0, cluster.getCircuitBreakers().getThresholds(0).getRetryBudget().getBudgetPercent().getValue());
        assertEquals(3, cluster.getCircuitBreakers().getThresholds(0).getRetryBudget().getMinRetryConcurrency().getValue());
    }

    @Test
    void endpointsCarryPriorityWeightAndHealth() {
        ClusterLoadAssignment assignment = EndpointResources.loadAssignment("c", topology, EnvoyResources::toIp);

        assertEquals(0, assignment.getEndpoints(0).getPriority());
        assertEquals(1, assignment.getEndpoints(1).getPriority());
        assertEquals(HealthStatus.UNHEALTHY, assignment.getEndpoints(1).getLbEndpoints(0).getHealthStatus());
        assertEquals("127.0.0.1", assignment.getEndpoints(0).getLbEndpoints(0).getEndpoint().getAddress()
                .getSocketAddress().getAddress());
    }
}
