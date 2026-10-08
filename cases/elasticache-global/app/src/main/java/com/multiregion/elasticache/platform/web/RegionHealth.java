package com.multiregion.elasticache.platform.web;

public record RegionHealth(
        String status,
        String region,
        boolean cacheConnected,
        String cacheRole) {
}
