package com.multiregion.xds;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Resources for proxyless gRPC clients ({@code xds:///products}): the client itself does priority
 * failover (gRFC A27), locality weights and outlier detection (gRFC A50), with no sidecar.
 */
final class GrpcResources {

    static final String SERVICE = "products";

    private GrpcResources() {
    }

    static Snapshot snapshot(Topology topology, String version) {
        return Snapshot.create(
                List.of(cluster()),
                List.of(EndpointResources.loadAssignment(SERVICE, topology, UnaryOperator.identity())),
                List.of(listener()),
                List.of(),
                List.of(),
                version);
    }

    static Listener listener() {
        RouteConfiguration routes = RouteConfiguration.newBuilder()
                .setName(SERVICE + "-routes")
                .addVirtualHosts(VirtualHost.newBuilder()
                        .setName(SERVICE)
                        .addDomains("*")
                        .addRoutes(Route.newBuilder()
                                .setMatch(RouteMatch.newBuilder().setPrefix(""))
                                .setRoute(RouteAction.newBuilder().setCluster(SERVICE))))
                .build();
        HttpConnectionManager manager = HttpConnectionManager.newBuilder()
                .setRouteConfig(routes)
                .addHttpFilters(HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                .build();
        return Listener.newBuilder()
                .setName(SERVICE)
                .setApiListener(ApiListener.newBuilder().setApiListener(Any.pack(manager)))
                .build();
    }

    static Cluster cluster() {
        return Cluster.newBuilder()
                .setName(SERVICE)
                .setType(Cluster.DiscoveryType.EDS)
                .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                        .setEdsConfig(ConfigSource.newBuilder()
                                .setAds(AggregatedConfigSource.getDefaultInstance())
                                .setResourceApiVersion(ApiVersion.V3)))
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setOutlierDetection(outlierDetection())
                .build();
    }

    /** Same numbers as the ServiceTalk router: 3 failures, 5s base ejection, 5 min cap. */
    static OutlierDetection outlierDetection() {
        return OutlierDetection.newBuilder()
                .setConsecutive5Xx(UInt32Value.of(3))
                .setInterval(Duration.newBuilder().setSeconds(1))
                .setBaseEjectionTime(Duration.newBuilder().setSeconds(5))
                .setMaxEjectionTime(Duration.newBuilder().setSeconds(300))
                .setMaxEjectionPercent(UInt32Value.of(50))
                .build();
    }
}
