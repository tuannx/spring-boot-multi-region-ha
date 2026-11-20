package com.multiregion.xds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The whole routing decision for one service: which regions exist, their priority, weight and
 * health. Changing it and publishing it is the only thing an operator does; every client follows.
 */
public record Topology(List<RegionState> regions) {

    public Topology {
        regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("regions");
        }
        TreeSet<Integer> priorities = new TreeSet<>();
        regions.forEach(r -> priorities.add(r.priority()));
        int expected = 0;
        for (int p : priorities) {
            if (p != expected++) {
                // gRFC A27 and Envoy both expect priorities 0..N without gaps.
                throw new IllegalArgumentException("priorities must be contiguous from 0: " + priorities);
            }
        }
    }

    public RegionState region(String name) {
        return regions.stream().filter(r -> r.region().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown region " + name));
    }

    /** Marks one region healthy or not, leaving priorities and weights as they are. */
    public Topology withHealthy(String name, boolean healthy) {
        region(name);
        List<RegionState> next = new ArrayList<>();
        for (RegionState r : regions) {
            next.add(r.region().equals(name) ? r.withHealthy(healthy) : r);
        }
        return new Topology(next);
    }

    /** Puts the named regions in priority 0 with the given weights; the others keep their order behind them. */
    public Topology withWeights(Map<String, Integer> weights) {
        weights.keySet().forEach(this::region);
        List<RegionState> next = new ArrayList<>();
        int nextPriority = 1;
        for (RegionState r : regions) {
            Integer weight = weights.get(r.region());
            next.add(weight != null ? r.withPriorityAndWeight(0, weight) : r.withPriorityAndWeight(nextPriority++, r.weight()));
        }
        return new Topology(next);
    }
}
