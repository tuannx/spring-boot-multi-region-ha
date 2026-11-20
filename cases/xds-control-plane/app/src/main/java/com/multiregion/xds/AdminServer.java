package com.multiregion.xds;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The operator's controls, over plain HTTP so a demo needs only curl.
 *
 * <pre>
 * GET  /topology
 * POST /drain?region=us-east-1      publish the region as UNHEALTHY
 * POST /restore?region=us-east-1    publish it HEALTHY again
 * POST /weights?us-east-1=90&amp;eu-west-1=10   same priority, split by weight
 * </pre>
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final XdsControlPlane controlPlane;
    private final Topology initial;

    public AdminServer(int port, XdsControlPlane controlPlane) throws IOException {
        this.controlPlane = controlPlane;
        this.initial = controlPlane.topology();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
    }

    public AdminServer start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            boolean post = "POST".equals(exchange.getRequestMethod());
            Topology now = controlPlane.topology();
            if (path.equals("/topology")) {
                send(exchange, 200, describe(now));
            } else if (post && path.equals("/drain")) {
                publish(exchange, now.withHealthy(required(query, "region"), false));
            } else if (post && path.equals("/restore")) {
                publish(exchange, now.withHealthy(required(query, "region"), true));
            } else if (post && path.equals("/weights")) {
                Map<String, Integer> weights = new LinkedHashMap<>();
                query.forEach((region, weight) -> weights.put(region, Integer.parseInt(weight)));
                publish(exchange, now.withWeights(weights));
            } else if (post && path.equals("/reset")) {
                publish(exchange, initial);
            } else {
                send(exchange, 404, "GET /topology, POST /drain, /restore, /weights, /reset\n");
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, e.getMessage() + "\n");
        }
    }

    private void publish(HttpExchange exchange, Topology next) throws IOException {
        String version = controlPlane.publish(next);
        send(exchange, 200, version + "\n" + describe(next));
    }

    private static String describe(Topology topology) {
        StringBuilder out = new StringBuilder();
        for (RegionState r : topology.regions()) {
            out.append(String.format("%s priority=%d weight=%d %s%n",
                    r.region(), r.priority(), r.weight(), r.healthy() ? "HEALTHY" : "UNHEALTHY"));
        }
        return out.toString();
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing ?" + name + "=");
        }
        return value;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
