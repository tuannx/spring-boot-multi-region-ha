package com.multiregion.xds;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts the control plane for the compose demo: US priority 0, EU priority 1. */
public final class XdsControlPlaneApplication {

    private XdsControlPlaneApplication() {
    }

    public static void main(String[] args) throws Exception {
        Topology topology = new Topology(List.of(
                new RegionState("us-east-1", env("US_ENDPOINT_HOST", "app-us"), envInt("US_ENDPOINT_PORT", 8080), 0, 100, true),
                new RegionState("eu-west-1", env("EU_ENDPOINT_HOST", "app-eu"), envInt("EU_ENDPOINT_PORT", 8081), 1, 100, true)));
        XdsControlPlane controlPlane = new XdsControlPlane(envInt("XDS_PORT", 18000), topology).start();
        AdminServer admin = new AdminServer(envInt("ADMIN_PORT", 18001), controlPlane).start();
        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xds-address-refresh");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleWithFixedDelay(controlPlane::republishIfAddressesChanged, 5, 5, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            refresher.shutdownNow();
            admin.close();
            try {
                controlPlane.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread.currentThread().join();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
