package com.multiregion.platform.web;

import com.multiregion.platform.failover.domain.FailoverActivationResult;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminResourceTest {

    @Test
    void activationFailureIsNotReportedAsSuccess() {
        AuroraTopologyGateway gateway = mock(AuroraTopologyGateway.class);
        FailoverControl failoverControl = mock(FailoverControl.class);
        when(failoverControl.forceFailover())
                .thenReturn(FailoverActivationResult.failed("promotion rejected"));
        AdminResource resource = new AdminResource(
                gateway, failoverControl, "eu-west-1", "secondary");

        ResponseEntity<Map<String, Object>> response = resource.activateFailover();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "activation_failed");
        assertThat(response.getBody()).containsEntry("error", "promotion rejected");
        verify(failoverControl).forceFailover();
    }
}
