package com.multiregion.ingest.application;

import com.multiregion.ingest.domain.MessageRouter;
import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;
import com.multiregion.ingest.port.QueuePublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Ingest Service use case: take one MTG message off the stream, route it
 * deterministically, publish it to the destination queue. This service does
 * no business processing; that is the Message Processor Service's job
 * (queue package). Keeping routing and processing in separate services lets
 * each scale and fail independently (Deere Kinesis-front pattern).
 */
public class IngestRoutingService {

    private static final Logger log = LoggerFactory.getLogger(IngestRoutingService.class);

    private final MessageRouter router;
    private final QueuePublisherPort publisher;

    public IngestRoutingService(MessageRouter router, QueuePublisherPort publisher) {
        this.router = Objects.requireNonNull(router, "router");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public RouteDecision onMessage(MtgMessage message) {
        RouteDecision decision = router.route(message);
        publisher.publish(message, decision);
        log.info("Ingest routed: messageId={} messageType={} sourceRegion={} queue={} targetRegion={} matchedRule={}",
                message.messageId(), message.messageType(), message.sourceRegion(),
                decision.logicalQueue(), decision.targetRegion(), decision.matchedRule());
        return decision;
    }
}
