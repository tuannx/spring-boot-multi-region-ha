package com.multiregion.xds;

import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

import java.util.function.UnaryOperator;

/** Builds the EDS resource: one locality per region, carrying its priority, weight and health. */
final class EndpointResources {

    private EndpointResources() {
    }

    /**
     * @param resolveHost turns a host name into what the client needs; Envoy EDS needs an IP,
     *                    gRPC accepts the name as given
     */
    static ClusterLoadAssignment loadAssignment(String clusterName, Topology topology, UnaryOperator<String> resolveHost) {
        ClusterLoadAssignment.Builder assignment = ClusterLoadAssignment.newBuilder().setClusterName(clusterName);
        for (RegionState region : topology.regions()) {
            assignment.addEndpoints(LocalityLbEndpoints.newBuilder()
                    .setLocality(Locality.newBuilder().setRegion(region.region()))
                    .setPriority(region.priority())
                    .setLoadBalancingWeight(UInt32Value.of(region.weight()))
                    .addLbEndpoints(LbEndpoint.newBuilder()
                            .setHealthStatus(region.healthy() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY)
                            .setEndpoint(Endpoint.newBuilder().setAddress(Address.newBuilder()
                                    .setSocketAddress(SocketAddress.newBuilder()
                                            .setAddress(resolveHost.apply(region.host()))
                                            .setPortValue(region.port()))))));
        }
        return assignment.build();
    }
}
