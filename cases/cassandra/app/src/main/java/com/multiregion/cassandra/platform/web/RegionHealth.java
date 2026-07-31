package com.multiregion.cassandra.platform.web;

import java.util.Map;

public record RegionHealth(
        String status,
        String region,
        String localDatacenter,
        int localNodesUp,
        int localNodesKnown,
        Map<String, Integer> upNodesByDatacenter) {
}
