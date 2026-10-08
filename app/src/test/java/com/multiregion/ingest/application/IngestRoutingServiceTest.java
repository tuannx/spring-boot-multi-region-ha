package com.multiregion.ingest.application;

import com.multiregion.ingest.domain.MessageRouter;
import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;
import com.multiregion.ingest.port.QueuePublisherPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestRoutingServiceTest {

    @Test
    void routesThenPublishesExactlyOnce() {
        List<RouteDecision> published = new ArrayList<>();
        QueuePublisherPort publisher = (message, decision) -> published.add(decision);
        IngestRoutingService service = new IngestRoutingService(
                new MessageRouter("orders", Map.of("MTG_ORDER", "orders")), publisher);

        MtgMessage message = new MtgMessage("m-9", "MTG_ORDER", "us-east-1", "m-9", "{}", Instant.now());
        RouteDecision decision = service.onMessage(message);

        assertThat(decision.logicalQueue()).isEqualTo("orders");
        assertThat(published).containsExactly(decision);
    }
}
