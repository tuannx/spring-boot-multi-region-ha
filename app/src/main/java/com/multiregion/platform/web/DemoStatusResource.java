package com.multiregion.platform.web;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import com.multiregion.queue.port.QueueManagementUseCase;
import com.multiregion.queue.port.QueueSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-call snapshot for the live demo dashboard ({@code /demo.html}).
 *
 * <p>Aggregates health, topology and queue state so the dashboard polls one
 * endpoint per region instead of three. Read-only; it never triggers failover.
 */
@RestController
@RequestMapping("/demo")
public class DemoStatusResource {

    private final AuroraTopologyGateway topologyGateway;
    private final FailoverControl failoverControl;
    private final QueueManagementUseCase queueManagement;
    private final String awsRegion;
    private final String regionRole;

    @Autowired
    public DemoStatusResource(
            AuroraTopologyGateway topologyGateway,
            FailoverControl failoverControl,
            MultiRegionConfig config,
            ObjectProvider<QueueManagementUseCase> queueManagement) {
        this(
                topologyGateway,
                failoverControl,
                config.awsRegion(),
                config.regionRole(),
                queueManagement.getIfAvailable());
    }

    DemoStatusResource(
            AuroraTopologyGateway topologyGateway,
            FailoverControl failoverControl,
            String awsRegion,
            String regionRole,
            QueueManagementUseCase queueManagement) {
        this.topologyGateway = topologyGateway;
        this.failoverControl = failoverControl;
        this.awsRegion = awsRegion;
        this.regionRole = regionRole;
        this.queueManagement = queueManagement;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean dbConnected = topologyGateway.isDatabaseConnected();
        String writerNode = "unknown";
        if (dbConnected) {
            try {
                writerNode = topologyGateway.currentWriter();
            } catch (RuntimeException e) {
                writerNode = "unknown";
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", dbConnected ? "UP" : "DEGRADED");
        response.put("region", awsRegion);
        response.put("role", regionRole);
        response.put("writerNode", writerNode);
        response.put("dbConnected", dbConnected);
        response.put("active", failoverControl.isActivated());
        response.put("instances", topologyInstances(dbConnected));
        response.put("queues", queueSection());
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    private List<Map<String, Object>> topologyInstances(boolean dbConnected) {
        List<Map<String, Object>> instances = new ArrayList<>();
        if (!dbConnected) {
            return instances;
        }
        try {
            for (TopologyInstance instance : topologyGateway.topology()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("serverId", instance.serverId());
                entry.put("isWriter", instance.isWriter());
                entry.put("cpu", instance.cpu());
                entry.put("lagMs", instance.lagMs());
                instances.add(entry);
            }
        } catch (RuntimeException e) {
            instances.clear();
        }
        return instances;
    }

    private Map<String, Object> queueSection() {
        Map<String, Object> queues = new HashMap<>();
        if (queueManagement == null) {
            queues.put("enabled", false);
            return queues;
        }
        try {
            QueueSnapshot snapshot = queueManagement.snapshot();
            queues.put("enabled", true);
            queues.put("states", snapshot.states());
            queues.put("localAssignments", snapshot.localAssignments());
            queues.put("takeoverAssignments", snapshot.takeoverAssignments());
            queues.put("runningAssignments", snapshot.runningAssignments());
        } catch (RuntimeException e) {
            queues.put("enabled", true);
            queues.put("error", e.getMessage());
        }
        return queues;
    }
}
