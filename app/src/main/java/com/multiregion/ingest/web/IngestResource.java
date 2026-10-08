package com.multiregion.ingest.web;

import com.multiregion.ingest.application.IngestRoutingService;
import com.multiregion.ingest.config.IngestProperties;
import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;
import com.multiregion.ingest.port.StreamProducerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Lab/demo surface for the ingest layer. POST /ingest/mtg puts one MTG
 * envelope on Kinesis when the Kinesis front is on; with mode direct it
 * routes straight to the destination queue so the two paths can be compared
 * without changing the caller. GET /ingest/status reports the active mode
 * and role split as evidence.
 */
@RestController
@RequestMapping("/ingest")
@ConditionalOnExpression("'${ingest.enabled:false}' == 'true' and '${service.role:combined}' != 'processor'")
public class IngestResource {

    private final IngestProperties properties;
    private final Optional<StreamProducerPort> producer;
    private final Optional<IngestRoutingService> routingService;

    public IngestResource(IngestProperties properties,
                          Optional<StreamProducerPort> producer,
                          Optional<IngestRoutingService> routingService) {
        this.properties = properties;
        this.producer = producer;
        this.routingService = routingService;
    }

    public record MtgRequest(String messageId, String messageType, String sourceRegion,
                             String partitionKey, String payload) {
    }

    public record IngestStatus(String mode, String streamName, String destinationType,
                               String defaultQueue, Map<String, String> routingRules) {
    }

    @GetMapping("/status")
    public IngestStatus status() {
        return new IngestStatus(properties.mode(), properties.streamName(),
                properties.destination().type(), properties.routing().defaultQueue(),
                properties.routing().rules());
    }

    @PostMapping("/mtg")
    public ResponseEntity<?> publish(@RequestBody MtgRequest request) {
        MtgMessage message = new MtgMessage(request.messageId(), request.messageType(),
                request.sourceRegion(),
                request.partitionKey() == null || request.partitionKey().isBlank()
                        ? request.messageId() : request.partitionKey(),
                request.payload(), Instant.now());
        if (properties.kinesisMode()) {
            StreamProducerPort stream = producer.orElseThrow(() ->
                    new IllegalStateException("Kinesis producer not available"));
            stream.put(message);
            return ResponseEntity.accepted().body(Map.of(
                    "accepted", true, "via", "kinesis", "stream", properties.streamName(),
                    "messageId", message.messageId()));
        }
        IngestRoutingService router = routingService.orElseThrow(() ->
                new IllegalStateException("Ingest routing not available"));
        RouteDecision decision = router.onMessage(message);
        return ResponseEntity.accepted().body(Map.of(
                "accepted", true, "via", "direct", "queue", decision.logicalQueue(),
                "region", decision.targetRegion(), "matchedRule", decision.matchedRule(),
                "messageId", message.messageId()));
    }
}
