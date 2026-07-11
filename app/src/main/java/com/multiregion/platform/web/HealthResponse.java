package com.multiregion.platform.web;

import java.time.Instant;

public record HealthResponse(
        String status,
        String region,
        String role,
        String writerNode,
        boolean dbConnected,
        boolean active,
        String timestamp
) {

    public HealthResponse(
            String status,
            String region,
            String role,
            String writerNode,
            boolean dbConnected,
            boolean active) {
        this(status, region, role, writerNode, dbConnected, active, Instant.now().toString());
    }
}
