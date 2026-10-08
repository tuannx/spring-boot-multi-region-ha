package com.multiregion.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which deployable role this process plays ({@code service.role}).
 * combined = Ingest Service + Message Processor Service in one process
 * (default, keeps the current single-image lab working). ingest = only the
 * Kinesis routing layer. processor = only the queue listeners. Splitting
 * roles is a config choice, not a code fork: the same image runs both.
 */
@ConfigurationProperties(prefix = "service")
public record ServiceRoleProperties(
        @DefaultValue("combined") String role) {

    public boolean runsIngest() {
        return role == null || "combined".equalsIgnoreCase(role) || "ingest".equalsIgnoreCase(role);
    }

    public boolean runsProcessor() {
        return role == null || "combined".equalsIgnoreCase(role) || "processor".equalsIgnoreCase(role);
    }

    public boolean isIngestOnly() {
        return "ingest".equalsIgnoreCase(role);
    }

    public boolean isProcessorOnly() {
        return "processor".equalsIgnoreCase(role);
    }
}
