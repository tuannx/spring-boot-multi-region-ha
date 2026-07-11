package com.multiregion.platform.web;

import com.multiregion.platform.failover.domain.FailoverActivationResult;
import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin controller for failover management and topology inspection.
 */
@RestController
@RequestMapping("/admin")
public class AdminResource {

    private static final Logger log = LoggerFactory.getLogger(AdminResource.class);

    private final AuroraTopologyGateway topologyGateway;
    private final FailoverControl failoverControl;
    private final String awsRegion;
    private final String regionRole;

    public AdminResource(
            AuroraTopologyGateway topologyGateway,
            FailoverControl failoverControl,
            @Value("${AWS_REGION:us-east-1}") String awsRegion,
            @Value("${REGION_ROLE:primary}") String regionRole) {
        this.topologyGateway = topologyGateway;
        this.failoverControl = failoverControl;
        this.awsRegion = awsRegion;
        this.regionRole = regionRole;
    }

    /**
     * Force-activate failover for this region.
     */
    @PostMapping("/failover-activate")
    public ResponseEntity<Map<String, Object>> activateFailover() {
        log.warn("Manual failover activation requested: region={} role={}", awsRegion, regionRole);
        FailoverActivationResult result = failoverControl.forceFailover();

        if (result.successful()) {
            log.info("Manual failover activation completed: region={} status={}",
                    awsRegion, result.status());
        } else {
            log.error("Manual failover activation failed: region={} status={} detail={}",
                    awsRegion, result.status(), result.detail());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", result.successful() ? "activated" : "activation_failed");
        response.put("region", awsRegion);
        response.put("role", regionRole);
        response.put("message", result.successful()
                ? "Failover activated for region: " + awsRegion
                : "Failover activation failed for region: " + awsRegion);
        if (!result.successful()) {
            response.put("error", result.detail());
        }
        response.put("timestamp", new Date().toString());

        return result.successful()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Get current Aurora cluster topology.
     */
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        Map<String, Object> response = new HashMap<>();
        response.put("region", awsRegion);
        response.put("role", regionRole);
        response.put("timestamp", new Date().toString());

        List<Map<String, Object>> instances = new ArrayList<>();
        try {
            for (TopologyInstance topologyInstance : topologyGateway.topology()) {
                Map<String, Object> instance = new HashMap<>();
                instance.put("serverId", topologyInstance.serverId());
                instance.put("isWriter", topologyInstance.isWriter());
                instance.put("cpu", topologyInstance.cpu());
                instance.put("lagMs", topologyInstance.lagMs());
                instances.add(instance);
            }
            response.put("instances", instances);
            response.put("dbConnected", true);
        } catch (Exception e) {
            response.put("dbConnected", false);
            response.put("error", e.getMessage());
            response.put("instances", Collections.emptyList());
        }

        return ResponseEntity.ok(response);
    }
}
