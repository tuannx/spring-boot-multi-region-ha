package com.multiregion.ingest.logging;

import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;
import com.multiregion.ingest.port.QueuePublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Log-only destination for local dev/tests: no AWS, no broker required. */
public class LoggingQueuePublisher implements QueuePublisherPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingQueuePublisher.class);

    @Override
    public void publish(MtgMessage message, RouteDecision decision) {
        log.info("Ingest publish (logging): messageId={} queue={} region={} payload={}",
                message.messageId(), decision.logicalQueue(), decision.targetRegion(), message.payload());
    }
}
