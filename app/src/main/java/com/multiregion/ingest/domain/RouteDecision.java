package com.multiregion.ingest.domain;

import java.util.Objects;

/**
 * Deterministic routing outcome for one MTG message: which logical queue in
 * which region the Ingest Service forwards it to, and why.
 * {@code matchedRule} is the evidence: the messageType rule that fired, or
 * {@code "default"} when no rule matched.
 */
public record RouteDecision(
        String logicalQueue,
        String targetRegion,
        String matchedRule) {

    public RouteDecision {
        Objects.requireNonNull(logicalQueue, "logicalQueue");
        Objects.requireNonNull(targetRegion, "targetRegion");
        Objects.requireNonNull(matchedRule, "matchedRule");
        if (logicalQueue.isBlank() || targetRegion.isBlank()) {
            throw new IllegalArgumentException("logicalQueue and targetRegion must not be blank");
        }
    }
}
