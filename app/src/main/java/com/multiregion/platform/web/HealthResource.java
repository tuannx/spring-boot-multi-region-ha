package com.multiregion.platform.web;

import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check controller providing region-aware status.
 */
@RestController
public class HealthResource {

    private final AuroraTopologyGateway topologyGateway;
    private final FailoverControl failoverControl;
    private final String awsRegion;
    private final String regionRole;

    public HealthResource(
            AuroraTopologyGateway topologyGateway,
            FailoverControl failoverControl,
            @Value("${AWS_REGION:us-east-1}") String awsRegion,
            @Value("${REGION_ROLE:primary}") String regionRole) {
        this.topologyGateway = topologyGateway;
        this.failoverControl = failoverControl;
        this.awsRegion = awsRegion;
        this.regionRole = regionRole;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbConnected = isDatabaseConnected();
        String writerNode = dbConnected ? getWriterNode() : "unknown";
        String status = dbConnected ? "UP" : "DEGRADED";

        return ResponseEntity.ok(new HealthResponse(
                status,
                awsRegion,
                regionRole,
                writerNode,
                dbConnected,
                failoverControl.isActivated()
        ));
    }

    private boolean isDatabaseConnected() {
        return topologyGateway.isDatabaseConnected();
    }

    private String getWriterNode() {
        try {
            return topologyGateway.currentWriter();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
