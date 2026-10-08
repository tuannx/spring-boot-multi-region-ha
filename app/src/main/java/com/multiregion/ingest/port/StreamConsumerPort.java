package com.multiregion.ingest.port;

/**
 * Inbound port of the Ingest Service: a long-running stream consumer
 * (Kinesis) whose lifecycle is owned by Spring. Kept minimal on purpose:
 * the consumer pulls records and hands each one to the routing use case;
 * checkpointing stays inside the adapter.
 */
public interface StreamConsumerPort {

    void start();

    void stop();

    boolean isRunning();
}
