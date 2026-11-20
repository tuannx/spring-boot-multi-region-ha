package com.multiregion.xds;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.retry.host.previous_hosts.v3.PreviousHostsPredicate;
import io.envoyproxy.envoy.extensions.retry.priority.previous_priorities.v3.PreviousPrioritiesConfig;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.Percent;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resources for an Envoy edge proxy in front of the Spring apps. Every rule that the hand-written
 * ServiceTalk router implements in Java (PR #120) is a field here.
 */
final class EnvoyResources {

    static final String LISTENER = "ingress_http";
    static final String CLUSTER = "products_http";
    static final int LISTEN_PORT = 10000;
    static final String IDEMPOTENT_METHODS = "GET|HEAD|OPTIONS|TRACE|PUT|DELETE";

    private EnvoyResources() {
    }

    static Snapshot snapshot(Topology topology, String version) {
        return Snapshot.create(
                List.of(cluster()),
                List.of(EndpointResources.loadAssignment(CLUSTER, topology, EnvoyResources::toIp)),
                List.of(listener()),
                List.of(),
                List.of(),
                version);
    }

    /** Envoy EDS endpoints must be IPs; compose names are resolved when the snapshot is built. */
    static String toIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            // Publish the name anyway; Envoy rejects it and keeps the last good config.
            return host;
        }
    }

    static Listener listener() {
        HttpConnectionManager manager = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_http")
                .setRouteConfig(RouteConfiguration.newBuilder()
                        .setName("products-routes")
                        .addVirtualHosts(VirtualHost.newBuilder()
                                .setName("products")
                                .addDomains("*")
                                .addRoutes(idempotentRoute())
                                .addRoutes(unsafeRoute())))
                .addHttpFilters(HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                .build();
        return Listener.newBuilder()
                .setName(LISTENER)
                .setAddress(Address.newBuilder().setSocketAddress(SocketAddress.newBuilder()
                        .setAddress("0.0.0.0").setPortValue(LISTEN_PORT)))
                .addFilterChains(FilterChain.newBuilder().addFilters(Filter.newBuilder()
                        .setName("envoy.filters.network.http_connection_manager")
                        .setTypedConfig(Any.pack(manager))))
                .build();
    }

    /** GET, PUT, DELETE...: retry after a timeout, reset or 502/503/504, like RetryPolicy.mayRetryAfterError. */
    static Route idempotentRoute() {
        return Route.newBuilder()
                .setName("idempotent")
                .setMatch(RouteMatch.newBuilder().setPrefix("/").addHeaders(HeaderMatcher.newBuilder()
                        .setName(":method")
                        .setStringMatch(StringMatcher.newBuilder()
                                .setSafeRegex(RegexMatcher.newBuilder().setRegex(IDEMPOTENT_METHODS)))))
                .setRoute(routeAction("connect-failure,reset,gateway-error"))
                .build();
    }

    /** POST, PATCH...: retry only when the connection was never made, so a write is never sent twice. */
    static Route unsafeRoute() {
        return Route.newBuilder()
                .setName("non-idempotent")
                .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                .setRoute(routeAction("connect-failure"))
                .build();
    }

    private static RouteAction routeAction(String retryOn) {
        return RouteAction.newBuilder()
                .setCluster(CLUSTER)
                .setTimeout(millis(1600))
                .setRetryPolicy(RetryPolicy.newBuilder()
                        .setRetryOn(retryOn)
                        .setNumRetries(UInt32Value.of(1))
                        .setPerTryTimeout(millis(800))
                        // Send the retry to another priority (EU), not back to the region that failed.
                        .setRetryPriority(RetryPolicy.RetryPriority.newBuilder()
                                .setName("envoy.retry_priorities.previous_priorities")
                                .setTypedConfig(Any.pack(PreviousPrioritiesConfig.newBuilder()
                                        .setUpdateFrequency(1).build())))
                        .addRetryHostPredicate(RetryPolicy.RetryHostPredicate.newBuilder()
                                .setName("envoy.retry_host_predicates.previous_hosts")
                                .setTypedConfig(Any.pack(PreviousHostsPredicate.getDefaultInstance()))))
                .build();
    }

    static Cluster cluster() {
        return Cluster.newBuilder()
                .setName(CLUSTER)
                .setType(Cluster.DiscoveryType.EDS)
                .setConnectTimeout(millis(500))
                .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                        .setEdsConfig(ConfigSource.newBuilder()
                                .setAds(AggregatedConfigSource.getDefaultInstance())
                                .setResourceApiVersion(ApiVersion.V3)))
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setCommonLbConfig(Cluster.CommonLbConfig.newBuilder()
                        .setLocalityWeightedLbConfig(Cluster.CommonLbConfig.LocalityWeightedLbConfig.getDefaultInstance()))
                .addHealthChecks(HealthCheck.newBuilder()
                        .setTimeout(millis(500))
                        .setInterval(millis(1000))
                        .setUnhealthyThreshold(UInt32Value.of(2))
                        .setHealthyThreshold(UInt32Value.of(2))
                        .setHttpHealthCheck(HealthCheck.HttpHealthCheck.newBuilder().setPath("/actuator/health")))
                .setOutlierDetection(GrpcResources.outlierDetection())
                .setCircuitBreakers(CircuitBreakers.newBuilder().addThresholds(CircuitBreakers.Thresholds.newBuilder()
                        .setRetryBudget(CircuitBreakers.Thresholds.RetryBudget.newBuilder()
                                .setBudgetPercent(Percent.newBuilder().setValue(20.0))
                                .setMinRetryConcurrency(UInt32Value.of(3)))))
                .build();
    }

    private static Duration millis(long value) {
        return Duration.newBuilder().setSeconds(value / 1000).setNanos((int) (value % 1000) * 1_000_000).build();
    }
}
