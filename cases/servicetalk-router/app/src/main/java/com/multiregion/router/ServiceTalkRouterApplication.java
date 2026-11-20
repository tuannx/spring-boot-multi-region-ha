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
        long routeTimeoutMs = getEnvLong("ROUTE_TIMEOUT_MS", requestTimeoutMs * 2);
        long maxEjectionMs = getEnvLong("MAX_EJECTION_MS", RouterPolicy.DEFAULT_MAX_EJECTION.toMillis());
        int retryBudgetPercent = getEnvInt("RETRY_BUDGET_PERCENT", RouterPolicy.DEFAULT_RETRY_BUDGET_PERCENT);
        int minRetryConcurrency = getEnvInt("RETRY_MIN_CONCURRENCY", RouterPolicy.DEFAULT_MIN_RETRY_CONCURRENCY);
        boolean healthCheckEnabled = !"false".equalsIgnoreCase(System.getenv("HEALTH_CHECK_ENABLED"));
        Map<String, String> environment = routerEnvironment(System.getenv());

        LOGGER.info("Initializing ServiceTalk Multi-Region Router...");
        LOGGER.info("  Listening Port: {}", routerPort);
        List<RouterEndpoints.EndpointSpec> specs = RouterEndpoints.specs(environment);
        for (RouterEndpoints.EndpointSpec spec : specs) {
            LOGGER.info("  Region {} Endpoint: {}:{}", spec.region(), spec.host(), spec.port());
        }
        LOGGER.info("  Outlier Failure Threshold: {}, Cooldown: {}ms", failureThreshold, cooldownMs);
        LOGGER.info("  Connect Timeout: {}ms, Per-try Timeout: {}ms, Route Timeout: {}ms",
                connectTimeoutMs, requestTimeoutMs, routeTimeoutMs);
        LOGGER.info("  Max Ejection: {}ms, Retry Budget: {}% (min {})",
                maxEjectionMs, retryBudgetPercent, minRetryConcurrency);

        List<StreamingHttpClient> clients = new ArrayList<>();
        List<RegionEndpoint> endpoints = new ArrayList<>();
        for (RouterEndpoints.EndpointSpec spec : specs) {
            StreamingHttpClient client = HttpClients.forSingleAddress(spec.host(), spec.port())
                    .socketOption(ServiceTalkSocketOptions.CONNECT_TIMEOUT, connectTimeoutMs)
                    .buildStreaming();
            clients.add(client);
            endpoints.add(new RegionEndpoint(spec.region(), spec.host(), spec.port(), spec.priority(), client));
        }

        RouterPolicy policy = new RouterPolicy(failureThreshold,
                Duration.ofMillis(cooldownMs),
                Duration.ofMillis(maxEjectionMs),
                Duration.ofMillis(requestTimeoutMs),
                Duration.ofMillis(routeTimeoutMs),
                retryBudgetPercent,
                minRetryConcurrency);
        LocalityPriorityRouter router = new LocalityPriorityRouter(endpoints, policy);

        ActiveHealthChecker healthChecker = null;
        if (healthCheckEnabled) {
            ActiveHealthChecker.Config healthConfig = new ActiveHealthChecker.Config(
                    getEnvString("HEALTH_CHECK_PATH", "/actuator/health"),
                    Duration.ofMillis(getEnvLong("HEALTH_CHECK_INTERVAL_MS", 1000L)),
                    Duration.ofMillis(getEnvLong("HEALTH_CHECK_TIMEOUT_MS", 500L)),
                    getEnvInt("HEALTH_UNHEALTHY_THRESHOLD", 2),
                    getEnvInt("HEALTH_HEALTHY_THRESHOLD", 2),
                    Duration.ofMillis(cooldownMs));
            LOGGER.info("  Active Health Check: {} every {}ms (timeout {}ms, unhealthy {}, healthy {})",
                    healthConfig.path(), healthConfig.interval().toMillis(), healthConfig.timeout().toMillis(),
                    healthConfig.unhealthyThreshold(), healthConfig.healthyThreshold());
            healthChecker = new ActiveHealthChecker(endpoints, healthConfig);
            healthChecker.start();
        } else {
            LOGGER.info("  Active Health Check: disabled");
        }
        final ActiveHealthChecker checkerToClose = healthChecker;

        HttpServerContext serverContext = HttpServers.forPort(routerPort)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> router.route(request, responseFactory));

        LOGGER.info("ServiceTalk Multi-Region Router successfully bound on port {}", serverContext.listenAddress());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down ServiceTalk Multi-Region Router...");
            try {
                if (checkerToClose != null) {
                    checkerToClose.close();
                }
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

    private static String getEnvString(String name, String defaultValue) {
        String val = System.getenv(name);
        return val == null || val.isBlank() ? defaultValue : val.trim();
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
