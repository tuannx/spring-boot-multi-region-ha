package com.multiregion.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RouterEndpoints {

    public record EndpointSpec(String region, String host, int port, int priority) {
    }

    private RouterEndpoints() {
    }

    public static List<EndpointSpec> specs(Map<String, String> environment) {
        List<EndpointSpec> specs = new ArrayList<>();
        specs.add(spec(environment, "us-east-1", "US_ENDPOINT_HOST", "app-us", "US_ENDPOINT_PORT", 8080, 0));
        specs.add(spec(environment, "eu-west-1", "EU_ENDPOINT_HOST", "app-eu", "EU_ENDPOINT_PORT", 8081, 1));
        optional(specs, environment, "ap-southeast-1", "APSE1_ENDPOINT_HOST", "APSE1_ENDPOINT_PORT", 8082, 2);
        optional(specs, environment, "ap-northeast-1", "APNE1_ENDPOINT_HOST", "APNE1_ENDPOINT_PORT", 8083, 3);
        return List.copyOf(specs);
    }

    private static void optional(
            List<EndpointSpec> specs,
            Map<String, String> environment,
            String region,
            String hostKey,
            String portKey,
            int defaultPort,
            int priority) {
        String host = environment.get(hostKey);
        if (host == null || host.isBlank()) {
            return;
        }
        specs.add(new EndpointSpec(region, host.trim(), port(environment, portKey, defaultPort), priority));
    }

    private static EndpointSpec spec(
            Map<String, String> environment,
            String region,
            String hostKey,
            String defaultHost,
            String portKey,
            int defaultPort,
            int priority) {
        String host = environment.get(hostKey);
        String resolved = host == null || host.isBlank() ? defaultHost : host.trim();
        return new EndpointSpec(region, resolved, port(environment, portKey, defaultPort), priority);
    }

    private static int port(Map<String, String> environment, String key, int defaultPort) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultPort;
        }
    }
}
