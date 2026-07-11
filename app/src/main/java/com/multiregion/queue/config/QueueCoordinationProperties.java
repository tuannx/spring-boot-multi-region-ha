package com.multiregion.queue.config;

import com.multiregion.queue.port.QueueCoordinationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "queues")
public record QueueCoordinationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5000") long pollIntervalMs,
        @DefaultValue("60000") long takeoverPollIntervalMs,
        @DefaultValue("1800000") long takeoverMaxDurationMs,
        @DefaultValue("orders") List<String> names,
        @DefaultValue({"us-east-1", "eu-west-1"}) List<String> regions
) implements QueueCoordinationPolicy {

    public QueueCoordinationProperties {
        names = names == null ? List.of() : List.copyOf(names);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
