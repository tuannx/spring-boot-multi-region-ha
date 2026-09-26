package com.multiregion.platform.web;

import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;
import com.multiregion.queue.port.QueueManagementUseCase;
import com.multiregion.queue.port.QueueSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoStatusResourceTest {

    @Test
    void aggregatesHealthTopologyAndQueuesInOneSnapshot() {
        AuroraTopologyGateway gateway = mock(AuroraTopologyGateway.class);
        FailoverControl failoverControl = mock(FailoverControl.class);
        QueueManagementUseCase queues = mock(QueueManagementUseCase.class);
        when(gateway.isDatabaseConnected()).thenReturn(true);
        when(gateway.currentWriter()).thenReturn("postgres-us");
        when(gateway.topology()).thenReturn(List.of(
                new TopologyInstance("postgres-us", true, 10, 0),
                new TopologyInstance("postgres-eu", false, 8, 85)));
        when(failoverControl.isActivated()).thenReturn(false);
        when(queues.snapshot()).thenReturn(new QueueSnapshot(
                List.of(QueueRegionState.up("orders", "us-east-1")),
                List.of(new QueueListenerAssignment(
                        "orders", "us-east-1",
                        com.multiregion.queue.domain.ListenerMode.PRIMARY)),
                List.of(),
                List.of(new QueueListenerAssignment(
                        "orders", "us-east-1",
                        com.multiregion.queue.domain.ListenerMode.PRIMARY))));

        DemoStatusResource resource = new DemoStatusResource(
                gateway, failoverControl, "us-east-1", "primary", queues);

        ResponseEntity<Map<String, Object>> response = resource.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("region", "us-east-1")
                .containsEntry("writerNode", "postgres-us")
                .containsEntry("dbConnected", true)
                .containsEntry("active", false);
        assertThat((List<?>) response.getBody().get("instances")).hasSize(2);
        assertThat(queuesSection(response)).containsEntry("enabled", true);
    }

    @Test
    void reportsDegradedWithoutTopologyWhenDatabaseIsDown() {
        AuroraTopologyGateway gateway = mock(AuroraTopologyGateway.class);
        FailoverControl failoverControl = mock(FailoverControl.class);
        when(gateway.isDatabaseConnected()).thenReturn(false);

        DemoStatusResource resource = new DemoStatusResource(
                gateway, failoverControl, "us-east-1", "primary", null);

        ResponseEntity<Map<String, Object>> response = resource.status();

        assertThat(response.getBody())
                .containsEntry("status", "DEGRADED")
                .containsEntry("writerNode", "unknown")
                .containsEntry("dbConnected", false);
        assertThat((List<?>) response.getBody().get("instances")).isEmpty();
        assertThat(queuesSection(response)).containsEntry("enabled", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queuesSection(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("queues");
    }
}
