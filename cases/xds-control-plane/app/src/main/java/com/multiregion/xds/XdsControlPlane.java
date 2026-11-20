package com.multiregion.xds;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves one {@link Topology} over ADS to two kinds of client: Envoy proxies (node id starting
 * {@code envoy}) and proxyless gRPC clients (everything else). {@link #publish} pushes a new
 * version to every connected client; none of them restarts.
 */
public final class XdsControlPlane implements AutoCloseable {

    static final String ENVOY_GROUP = "envoy";
    static final String GRPC_GROUP = "grpc";

    private static final Logger LOGGER = LoggerFactory.getLogger(XdsControlPlane.class);

    private final SimpleCache<String> cache = new SimpleCache<>(XdsControlPlane::group);
    private final AtomicLong version = new AtomicLong();
    private final AtomicReference<Topology> current = new AtomicReference<>();
    private final Server server;
    private List<String> resolvedHosts = List.of();

    public XdsControlPlane(int port, Topology initial) {
        publish(initial);
        V3DiscoveryServer discovery = new V3DiscoveryServer(cache);
        this.server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(discovery.getAggregatedDiscoveryServiceImpl())
                .addService(discovery.getClusterDiscoveryServiceImpl())
                .addService(discovery.getEndpointDiscoveryServiceImpl())
                .addService(discovery.getListenerDiscoveryServiceImpl())
                .addService(discovery.getRouteDiscoveryServiceImpl())
                .build();
    }

    static String group(Node node) {
        return node.getId().startsWith(ENVOY_GROUP) ? ENVOY_GROUP : GRPC_GROUP;
    }

    public XdsControlPlane start() throws IOException {
        server.start();
        LOGGER.info("xDS control plane listening on {}", server.getPort());
        return this;
    }

    public int port() {
        return server.getPort();
    }

    public Topology topology() {
        return current.get();
    }

    /** Builds both snapshots from the topology and hands them to every connected client. */
    public synchronized String publish(Topology topology) {
        resolvedHosts = resolve(topology);
        String next = "v" + version.incrementAndGet();
        cache.setSnapshot(GRPC_GROUP, GrpcResources.snapshot(topology, next));
        cache.setSnapshot(ENVOY_GROUP, EnvoyResources.snapshot(topology, next));
        current.set(topology);
        LOGGER.info("Published {}: {}", next, topology.regions());
        return next;
    }

    /**
     * Envoy needs IPs, which compose hands out when a container starts. Republishes the same
     * topology when an app container came up later or got a new IP.
     *
     * @return true when a new version was published
     */
    public synchronized boolean republishIfAddressesChanged() {
        Topology topology = current.get();
        if (resolve(topology).equals(resolvedHosts)) {
            return false;
        }
        publish(topology);
        return true;
    }

    private static List<String> resolve(Topology topology) {
        return topology.regions().stream().map(r -> EnvoyResources.toIp(r.host())).toList();
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
}
