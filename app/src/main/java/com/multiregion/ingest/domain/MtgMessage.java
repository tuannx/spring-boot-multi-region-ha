package com.multiregion.ingest.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * MTG message envelope as it arrives on the Kinesis ingest stream.
 * The name is the contract: producers must set messageId (idempotency key),
 * messageType (drives deterministic routing), sourceRegion and partitionKey
 * (Kinesis ordering key). Payload stays opaque bytes at this layer.
 */
public record MtgMessage(
        String messageId,
        String messageType,
        String sourceRegion,
        String partitionKey,
        String payload,
        Instant receivedAt) {

    public MtgMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(sourceRegion, "sourceRegion");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (messageId.isBlank() || messageType.isBlank() || partitionKey.isBlank()) {
            throw new IllegalArgumentException("messageId, messageType and partitionKey must not be blank");
        }
    }
}
