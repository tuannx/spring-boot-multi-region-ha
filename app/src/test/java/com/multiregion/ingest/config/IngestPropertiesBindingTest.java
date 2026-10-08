package com.multiregion.ingest.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class IngestPropertiesBindingTest {

    @Test
    void defaultsKeepKinesisLayerOff() {
        IngestProperties props = Binder.get(new MockEnvironment())
                .bind("ingest", Bindable.of(IngestProperties.class)).get();
        assertThat(props.enabled()).isFalse();
        assertThat(props.kinesisMode()).isFalse();
        assertThat(props.streamName()).isEqualTo("mtg-ingest");
        assertThat(props.routing().defaultQueue()).isEqualTo("orders");
    }

    @Test
    void bindsKinesisFrontSelection() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ingest.enabled", "true")
                .withProperty("ingest.mode", "kinesis")
                .withProperty("ingest.destination.type", "sqs")
                .withProperty("ingest.routing.rules.MTG_ORDER", "orders")
                .withProperty("ingest.routing.rules.MTG_PAYMENT", "billing");
        IngestProperties props = Binder.get(env)
                .bind("ingest", Bindable.of(IngestProperties.class)).get();
        assertThat(props.kinesisMode()).isTrue();
        assertThat(props.destination().type()).isEqualTo("sqs");
        assertThat(props.routing().rules()).containsEntry("MTG_PAYMENT", "billing");
        assertThat(props.destination().sqs().queueName("orders", "us-east-1"))
                .isEqualTo("orders-us-east-1");
    }

    @Test
    void roleSplitIsExclusive() {
        assertThat(new ServiceRoleProperties("ingest").runsProcessor()).isFalse();
        assertThat(new ServiceRoleProperties("ingest").runsIngest()).isTrue();
        assertThat(new ServiceRoleProperties("processor").runsIngest()).isFalse();
        assertThat(new ServiceRoleProperties("processor").runsProcessor()).isTrue();
        assertThat(new ServiceRoleProperties("combined").runsIngest()).isTrue();
        assertThat(new ServiceRoleProperties("combined").runsProcessor()).isTrue();
    }
}
