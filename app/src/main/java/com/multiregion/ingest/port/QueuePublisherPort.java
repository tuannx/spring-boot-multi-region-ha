package com.multiregion.ingest.port;

import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;

/**
 * Outbound port of the Ingest Service: publish one routed MTG message to its
 * destination queue (SQS in AWS, RabbitMQ bridge in the local lab, or log
 * sink in tests). Implementations live in adapter packages only; the domain
 * never depends on them.
 */
public interface QueuePublisherPort {

    void publish(MtgMessage message, RouteDecision decision);
}
