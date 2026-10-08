package com.multiregion.ingest.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Pure, deterministic router for the Ingest Service. No I/O, no framework.
 * Rule: messageType -> logical queue (exact match); target region is always
 * the message's sourceRegion so home-region processing semantics hold.
 * Unknown types fall back to the configured default queue with evidence
 * {@code matchedRule = "default"}. Same input always yields the same
 * RouteDecision.
 */
public final class MessageRouter {

    private final String defaultQueue;
    private final Map<String, String> rulesByMessageType;

    public MessageRouter(String defaultQueue, Map<String, String> rulesByMessageType) {
        this.defaultQueue = Objects.requireNonNull(defaultQueue, "defaultQueue");
        this.rulesByMessageType = rulesByMessageType == null
                ? Map.of()
                : Map.copyOf(rulesByMessageType);
        if (defaultQueue.isBlank()) {
            throw new IllegalArgumentException("defaultQueue must not be blank");
        }
    }

    public RouteDecision route(MtgMessage message) {
        Objects.requireNonNull(message, "message");
        String mapped = rulesByMessageType.get(message.messageType());
        if (mapped != null && !mapped.isBlank()) {
            return new RouteDecision(mapped, message.sourceRegion(), message.messageType());
        }
        return new RouteDecision(defaultQueue, message.sourceRegion(), "default");
    }

    public String defaultQueue() {
        return defaultQueue;
    }

    public Map<String, String> rulesByMessageType() {
        return rulesByMessageType;
    }
}
