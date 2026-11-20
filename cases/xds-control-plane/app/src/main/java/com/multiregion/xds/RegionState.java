package com.multiregion.xds;

import java.util.Objects;

/**
 * One region as the control plane publishes it.
 *
 * @param region   locality name, for example {@code us-east-1}
 * @param host     backend host or IP
 * @param port     backend port
 * @param priority 0 is tried first; a client moves to 1 only when 0 has no healthy endpoint
 * @param weight   share of traffic among regions with the same priority
 * @param healthy  false publishes the endpoint as UNHEALTHY, which drains it from every client
 */
public record RegionState(String region, String host, int port, int priority, int weight, boolean healthy) {

    public RegionState {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(host, "host");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1");
        }
    }

    RegionState withHealthy(boolean value) {
        return new RegionState(region, host, port, priority, weight, value);
    }

    RegionState withPriorityAndWeight(int newPriority, int newWeight) {
        return new RegionState(region, host, port, newPriority, newWeight, healthy);
    }
}
