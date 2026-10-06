package com.multiregion.router;

import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import io.servicetalk.http.netty.HttpServers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * ServiceTalk Multi-Region Router Application Entrypoint.
 */
public final class ServiceTalkRouterApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceTalkRouterApplication.class);

    public static void main(String[] args) throws Exception {
        int routerPort = getEnvInt("ROUTER_PORT", 8085);
        String usHost = getEnvString("US_ENDPOINT_HOST", getEnvString("TARGET_US_HOST", "app-us"));
        int usPort = getEnvInt("US_ENDPOINT_PORT", getEnvInt("TARGET_US_PORT", 8080));

        String euHost = getEnvString("EU_ENDPOINT_HOST", getEnvString("TARGET_EU_HOST", "app-eu"));
        int euPort = getEnvInt("EU_ENDPOINT_PORT", getEnvInt("TARGET_EU_PORT", 8081));

        int failureThreshold = getEnvInt("FAILURE_THRESHOLD", getEnvInt("OUTLIER_FAILURE_THRESHOLD", 3));
        long cooldownMs = getEnvLong("COOLDOWN_MS", getEnvLong("OUTLIER_COOLDOWN_MS", 5000L));
        long requestTimeoutMs = getEnvLong("REQUEST_TIMEOUT_MS", 800L);
        int connectTimeoutMs = getEnvInt("CONNECT_TIMEOUT_MS", 500);

        LOGGER.info("Initializing ServiceTalk Multi-Region Router...");
        LOGGER.info("  Listening Port: {}", routerPort);
        LOGGER.info("  Region US Endpoint: {}:{}", usHost, usPort);
        LOGGER.info("  Region EU Endpoint: {}:{}", euHost, euPort);
        LOGGER.info("  Outlier Failure Threshold: {}, Cooldown: {}ms", failureThreshold, cooldownMs);
        LOGGER.info("  Connect Timeout: {}ms, Request Timeout: {}ms", connectTimeoutMs, requestTimeoutMs);

        StreamingHttpClient clientUs = HttpClients.forSingleAddress(usHost, usPort)
                .socketOption(io.servicetalk.transport.api.ServiceTalkSocketOptions.CONNECT_TIMEOUT, connectTimeoutMs)
                .buildStreaming();

        StreamingHttpClient clientEu = HttpClients.forSingleAddress(euHost, euPort)
                .socketOption(io.servicetalk.transport.api.ServiceTalkSocketOptions.CONNECT_TIMEOUT, connectTimeoutMs)
                .buildStreaming();

        RegionEndpoint usEndpoint = new RegionEndpoint("us-east-1", usHost, usPort, 0, clientUs);
        RegionEndpoint euEndpoint = new RegionEndpoint("eu-west-1", euHost, euPort, 1, clientEu);

        Duration requestTimeout = Duration.ofMillis(requestTimeoutMs);
        LocalityPriorityRouter router = new LocalityPriorityRouter(usEndpoint, euEndpoint, failureThreshold, cooldownMs, requestTimeout);

        HttpServerContext serverContext = HttpServers.forPort(routerPort)
                .listenStreamingAndAwait((ctx, request, responseFactory) -> router.route(request, responseFactory));

        LOGGER.info("ServiceTalk Multi-Region Router successfully bound on port {}", serverContext.listenAddress());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down ServiceTalk Multi-Region Router...");
            try {
                serverContext.closeGracefully();
                clientUs.closeGracefully();
                clientEu.closeGracefully();
            } catch (Exception e) {
                LOGGER.error("Error during shutdown", e);
            }
        }));

        serverContext.awaitShutdown();
    }

    private static String getEnvString(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.trim().isEmpty()) ? val.trim() : defaultValue;
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
