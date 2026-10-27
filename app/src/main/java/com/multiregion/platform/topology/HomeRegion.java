package com.multiregion.platform.topology;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum HomeRegion {
    US_EAST_1(
            "us-east-1",
            "postgres-us",
            "postgres-eu",
            "10",
            "8",
            true,
            "Regional Product US",
            "19.99"),
    EU_WEST_1(
            "eu-west-1",
            "postgres-eu",
            "postgres-us",
            "8",
            "10",
            false,
            "Regional Product EU",
            "39.99"),
    AP_SOUTHEAST_1(
            "ap-southeast-1",
            "postgres-apse1",
            "postgres-apne1",
            "6",
            "4",
            true,
            "Regional Product APSE1",
            "29.99"),
    AP_NORTHEAST_1(
            "ap-northeast-1",
            "postgres-apne1",
            "postgres-apse1",
            "4",
            "6",
            true,
            "Regional Product APNE1",
            "49.99");

    private final String id;
    private final String databaseHost;
    private final String peerHost;
    private final String cpu;
    private final String peerCpu;
    private final boolean homeWriter;
    private final String seedProductName;
    private final String seedProductPrice;

    HomeRegion(
            String id,
            String databaseHost,
            String peerHost,
            String cpu,
            String peerCpu,
            boolean homeWriter,
            String seedProductName,
            String seedProductPrice) {
        this.id = id;
        this.databaseHost = databaseHost;
        this.peerHost = peerHost;
        this.cpu = cpu;
        this.peerCpu = peerCpu;
        this.homeWriter = homeWriter;
        this.seedProductName = seedProductName;
        this.seedProductPrice = seedProductPrice;
    }

    public String id() {
        return id;
    }

    public String databaseHost() {
        return databaseHost;
    }

    public String peerHost() {
        return peerHost;
    }

    public String cpu() {
        return cpu;
    }

    public String peerCpu() {
        return peerCpu;
    }

    public boolean homeWriter() {
        return homeWriter;
    }

    public String seedProductName() {
        return seedProductName;
    }

    public String seedProductPrice() {
        return seedProductPrice;
    }

    public static Optional<HomeRegion> find(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        String normalized = regionId.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(region -> region.id.equals(normalized))
                .findFirst();
    }

    public static HomeRegion require(String regionId) {
        return find(regionId).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported AWS region for database migration: " + regionId));
    }
}
