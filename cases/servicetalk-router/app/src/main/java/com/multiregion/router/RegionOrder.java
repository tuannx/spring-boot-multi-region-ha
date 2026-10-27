package com.multiregion.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RegionOrder {

    private static final Map<String, String> PAIRS = Map.of(
            "us-east-1", "eu-west-1",
            "eu-west-1", "us-east-1",
            "ap-southeast-1", "ap-northeast-1",
            "ap-northeast-1", "ap-southeast-1");

    private RegionOrder() {
    }

    public static List<RegionEndpoint> forSource(String sourceRegion, List<RegionEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints");
        }
        RegionEndpoint primary = match(endpoints, sourceRegion)
                .or(() -> match(endpoints, "us-east-1"))
                .orElse(endpoints.get(0));
        List<RegionEndpoint> ordered = new ArrayList<>();
        ordered.add(primary);
        String pairId = PAIRS.get(primary.regionName());
        if (pairId != null) {
            match(endpoints, pairId)
                    .filter(pair -> !ordered.contains(pair))
                    .ifPresent(ordered::add);
        }
        endpoints.stream()
                .sorted(Comparator.comparingInt(RegionEndpoint::defaultPriority))
                .filter(endpoint -> !ordered.contains(endpoint))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static Optional<RegionEndpoint> match(List<RegionEndpoint> endpoints, String region) {
        if (region == null || region.isBlank()) {
            return Optional.empty();
        }
        return endpoints.stream()
                .filter(endpoint -> endpoint.regionName().equalsIgnoreCase(region.trim()))
                .findFirst();
    }
}
