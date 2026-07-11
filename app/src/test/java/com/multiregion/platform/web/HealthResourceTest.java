package com.multiregion.platform.web;

import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthResourceTest {

    @Test
    void secondaryHealthReportsRuntimeActivationState() {
        AuroraTopologyGateway gateway = mock(AuroraTopologyGateway.class);
        FailoverControl failoverControl = mock(FailoverControl.class);
        when(gateway.isDatabaseConnected()).thenReturn(true);
        when(gateway.currentWriter()).thenReturn("eu-writer");
        when(failoverControl.isActivated()).thenReturn(true);
        HealthResource resource = new HealthResource(
                gateway, failoverControl, "eu-west-1", "secondary");

        ResponseEntity<HealthResponse> response = resource.health();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().active()).isTrue();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().writerNode()).isEqualTo("eu-writer");
    }

    @Test
    void databaseOutageDoesNotRunASecondTopologyQuery() {
        AuroraTopologyGateway gateway = mock(AuroraTopologyGateway.class);
        FailoverControl failoverControl = mock(FailoverControl.class);
        when(gateway.isDatabaseConnected()).thenReturn(false);
        HealthResource resource = new HealthResource(
                gateway, failoverControl, "eu-west-1", "secondary");

        ResponseEntity<HealthResponse> response = resource.health();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("DEGRADED");
        assertThat(response.getBody().writerNode()).isEqualTo("unknown");
        verify(gateway, never()).currentWriter();
    }
}
