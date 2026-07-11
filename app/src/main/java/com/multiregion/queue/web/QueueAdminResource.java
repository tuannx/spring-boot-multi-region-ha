package com.multiregion.queue.web;

import com.multiregion.queue.domain.QueueHealthStatus;
import com.multiregion.queue.port.QueueManagementUseCase;
import com.multiregion.queue.port.QueueSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/queues")
public class QueueAdminResource {

    private final QueueManagementUseCase queueManagement;

    public QueueAdminResource(QueueManagementUseCase queueManagement) {
        this.queueManagement = queueManagement;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listQueues() {
        return ResponseEntity.ok(response(queueManagement.snapshot()));
    }

    @PostMapping("/{queueName}/{region}/down")
    public ResponseEntity<Map<String, Object>> markDown(
            @PathVariable String queueName,
            @PathVariable String region,
            @RequestParam(defaultValue = "manual queue down") String reason) {
        QueueSnapshot snapshot = queueManagement.updateStatus(
                queueName, region, QueueHealthStatus.DOWN, reason);
        return ResponseEntity.ok(statusResponse(
                queueName, region, QueueHealthStatus.DOWN, reason, snapshot));
    }

    @PostMapping("/{queueName}/{region}/up")
    public ResponseEntity<Map<String, Object>> markUp(
            @PathVariable String queueName,
            @PathVariable String region,
            @RequestParam(defaultValue = "manual queue up") String reason) {
        QueueSnapshot snapshot = queueManagement.updateStatus(
                queueName, region, QueueHealthStatus.UP, reason);
        return ResponseEntity.ok(statusResponse(
                queueName, region, QueueHealthStatus.UP, reason, snapshot));
    }

    private Map<String, Object> statusResponse(
            String queueName,
            String region,
            QueueHealthStatus status,
            String reason,
            QueueSnapshot snapshot) {
        Map<String, Object> response = response(snapshot);
        response.put("queueName", queueName);
        response.put("region", region);
        response.put("status", status);
        response.put("reason", reason);
        return response;
    }

    private Map<String, Object> response(QueueSnapshot snapshot) {
        Map<String, Object> response = new HashMap<>();
        response.put("states", snapshot.states());
        response.put("localAssignments", snapshot.localAssignments());
        response.put("takeoverAssignments", snapshot.takeoverAssignments());
        response.put("runningAssignments", snapshot.runningAssignments());
        return response;
    }
}
