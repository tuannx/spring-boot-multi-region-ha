package com.multiregion.router;

import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import io.servicetalk.http.netty.HttpServers;
import io.servicetalk.transport.api.ServiceTalkSocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceTalk Multi-Region Router Application Entrypoint.
 */
public final class ServiceTalkRouterApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceTalkRouterApplication.class);

    public static void main(String[] args) throws Exception {
        int routerPort = getEnvInt("ROUTER_PORT", 8085);
        int failureThreshold = getEnvInt("FAILURE_THRESHOLD", getEnvInt("OUTLIER_FAILURE_THRESHOLD", 3));
        long cooldownMs = getEnvLong("COOLDOWN_MS", getEnvLong("OUTLIER_COOLDOWN_MS", 5000L));
        long requestTimeoutMs = getEnvLong("REQUEST_TIMEOUT_MS", 800L);
        int connectTimeoutMs = getEnvInt("CONNECT_TIMEOUT_MS", 500);
        Map<String, String> environment = routerEnvironment(System.getenv());

        LOGGER.info("Initializing ServiceTalk Multi-Region Router...");
        LOGGER.info("  Listening Port: {}", routerPort);
        List<RouterEndpoints.EndpointSpec> specs = RouterEndpoints.specs(environment);
        for (RouterEndpoints.EndpointSpec spec : specs) {
            LOGGER.info("  Region {} Endpoint: {}:{}", spec.region(), spec.host(), spec.port());
        }
        LOGGER.info("  Outlier Failure Threshold: {}, Cooldown: {}ms", failureThreshold, cooldownMs);
        LOGGER.info("  Connect Timeout: {}ms, Request Timeout: {}ms", connectTimeoutMs, requestTimeoutMs);

        List<StreamingHttpClient> clients = new ArrayList<>();
        List<RegionEndpoint> endpoints = new ArrayList<>();
        for (RouterEndpoints.EndpointSpec spec : specs) {
            StreamingHttpClient client = HttpClients.forSingleAddress(spec.host(), spec.port())
                    .socketOption(ServiceTalkSocketOptions.CONNECT_TIMEOUT, connectTimeoutMs)
                    .buildStreaming();
            clients.add(client);
            endpoints.add(new RegionEndpoint(spec.region(), spec.host(), spec.port(), spec.priority(), client));
        }

        Duration requestTimeout = Duration.ofMillis(requestTimeoutMs);
        LocalityPriorityRouter router = new LocalityPriorityRouter(endpoints, failureThreshold, cooldownMs, requestTimeout);

        HttpServerContext serverContext = HttpServers.forPort(routerPort)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> router.route(request, responseFactory));

        LOGGER.info("ServiceTalk Multi-Region Router successfully bound on port {}", serverContext.listenAddress());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down ServiceTalk Multi-Region Router...");
            try {
                serverContext.closeGracefully();
                for (StreamingHttpClient client : clients) {
                    client.closeGracefully();
                }
            } catch (Exception e) {
                LOGGER.error("Error during shutdown", e);
            }
        }));

        serverContext.awaitShutdown();
    }

    static Map<String, String> routerEnvironment(Map<String, String> source) {
        Map<String, String> environment = new HashMap<>(source);
        copyIfAbsent(environment, "US_ENDPOINT_HOST", "TARGET_US_HOST");
        copyIfAbsent(environment, "US_ENDPOINT_PORT", "TARGET_US_PORT");
        copyIfAbsent(environment, "EU_ENDPOINT_HOST", "TARGET_EU_HOST");
        copyIfAbsent(environment, "EU_ENDPOINT_PORT", "TARGET_EU_PORT");
        return environment;
    }

    private static void copyIfAbsent(Map<String, String> environment, String target, String legacy) {
        if (blank(environment.get(target)) && !blank(environment.get(legacy))) {
            environment.put(target, environment.get(legacy));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int getEnvInt(String name, int defaultValue) {
        String val = System.getenv(name);
        if (val != null && !val.trim().isEmpty()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static long getEnvLong(String name, long defaultValue) {
        String val = System.getenv(name);
        if (val != null && !val.trim().isEmpty()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
