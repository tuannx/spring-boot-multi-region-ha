package com.multiregion.ingest.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRouterTest {

    private final MessageRouter router = new MessageRouter("orders",
            Map.of("MTG_ORDER", "orders", "MTG_PAYMENT", "billing"));

    @Test
    void routesKnownTypeToMappedQueueInSourceRegion() {
        RouteDecision decision = router.route(msg("MTG_PAYMENT", "eu-west-1"));
        assertThat(decision.logicalQueue()).isEqualTo("billing");
        assertThat(decision.targetRegion()).isEqualTo("eu-west-1");
        assertThat(decision.matchedRule()).isEqualTo("MTG_PAYMENT");
    }

    @Test
    void fallsBackToDefaultQueueForUnknownType() {
        RouteDecision decision = router.route(msg("MTG_UNKNOWN", "us-east-1"));
        assertThat(decision.logicalQueue()).isEqualTo("orders");
        assertThat(decision.matchedRule()).isEqualTo("default");
    }

    @Test
    void isDeterministicForSameInput() {
        MtgMessage message = msg("MTG_ORDER", "us-east-1");
        assertThat(router.route(message)).isEqualTo(router.route(message));
    }

    private static MtgMessage msg(String type, String region) {
        return new MtgMessage("m-1", type, region, "m-1", "{}", Instant.now());
    }
}
