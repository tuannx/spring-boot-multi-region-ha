package com.multiregion.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Configuration for the optional Kinesis ingest layer ({@code ingest.*}).
 * Defaults keep the layer OFF so existing deployments are untouched:
 * producers keep the legacy direct path to the processor queues. Set
 * {@code ingest.enabled=true} and {@code ingest.mode=kinesis} to put
 * Kinesis in front of SQS.
 */
@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("direct") String mode,
        @DefaultValue("mtg-ingest") String streamName,
        @DefaultValue("") String streamRegion,
        @DefaultValue("") String endpointOverride,
        @DefaultValue("1000") long shardPollIntervalMs,
        @DefaultValue("100") int maxRecordsPerPoll,
        @DefaultValue("TRIM_HORIZON") String position,
        Destination destination,
        Routing routing) {

    public IngestProperties {
        destination = destination == null ? new Destination(null, null) : destination;
        routing = routing == null ? new Routing(null, null) : routing;
    }

    public boolean kinesisMode() {
        return enabled && "kinesis".equalsIgnoreCase(mode);
    }

    public record Destination(
            @DefaultValue("logging") String type,
            Sqs sqs) {

        public Destination {
            type = type == null || type.isBlank() ? "logging" : type;
            sqs = sqs == null ? new Sqs(null, false, null) : sqs;
        }
    }

    public record Sqs(
            @DefaultValue("%s-%s") String queueNamePattern,
            @DefaultValue("false") boolean fifo,
            @DefaultValue("") String endpointOverride) {

        public Sqs {
            queueNamePattern = queueNamePattern == null || queueNamePattern.isBlank()
                    ? "%s-%s" : queueNamePattern;
            endpointOverride = endpointOverride == null ? "" : endpointOverride;
        }

        public String queueName(String logicalQueue, String region) {
            return String.format(queueNamePattern, logicalQueue, region);
        }
    }

    public record Routing(
            @DefaultValue("orders") String defaultQueue,
            Map<String, String> rules) {

        public Routing {
            defaultQueue = defaultQueue == null || defaultQueue.isBlank() ? "orders" : defaultQueue;
            rules = rules == null ? Map.of() : Map.copyOf(rules);
        }
    }
}
