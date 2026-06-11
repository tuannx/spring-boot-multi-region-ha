package com.multiregion.queue;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/admin/queues")
public class QueueAdminController {

    private final QueueRegionStateRepository repository;
    private final LocalQueueListenerCoordinator localCoordinator;
    private final DynamicQueueListenerCoordinator takeoverCoordinator;

    public QueueAdminController(
            QueueRegionStateRepository repository,
            ObjectProvider<LocalQueueListenerCoordinator> localCoordinatorProvider,
            ObjectProvider<DynamicQueueListenerCoordinator> takeoverCoordinatorProvider) {
        this.repository = repository;
        this.localCoordinator = localCoordinatorProvider.getIfAvailable();
        this.takeoverCoordinator = takeoverCoordinatorProvider.getIfAvailable();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listQueues() {
        Map<String, Object> response = new HashMap<>();
        response.put("states", repository.findAll());
        response.put("localAssignments", localAssignments());
        response.put("takeoverAssignments", takeoverAssignments());
        response.put("runningAssignments", runningAssignments());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{queueName}/{region}/down")
    public ResponseEntity<Map<String, Object>> markDown(
            @PathVariable String queueName,
            @PathVariable String region,
            @RequestParam(defaultValue = "manual queue down") String reason) {
        repository.markDown(queueName, region, reason);
        reconcile();
        return ResponseEntity.ok(statusResponse(queueName, region, QueueHealthStatus.DOWN, reason));
    }

    @PostMapping("/{queueName}/{region}/up")
    public ResponseEntity<Map<String, Object>> markUp(
            @PathVariable String queueName,
            @PathVariable String region,
            @RequestParam(defaultValue = "manual queue up") String reason) {
        repository.markUp(queueName, region, reason);
        reconcile();
        return ResponseEntity.ok(statusResponse(queueName, region, QueueHealthStatus.UP, reason));
    }

    private void reconcile() {
        if (localCoordinator != null) {
            localCoordinator.reconcileLocalListeners();
        }
        if (takeoverCoordinator != null) {
            takeoverCoordinator.reconcile();
        }
    }

    private Map<String, Object> statusResponse(
            String queueName,
            String region,
            QueueHealthStatus status,
            String reason) {
        Map<String, Object> response = new HashMap<>();
        response.put("queueName", queueName);
        response.put("region", region);
        response.put("status", status);
        response.put("reason", reason);
        response.put("localAssignments", localAssignments());
        response.put("takeoverAssignments", takeoverAssignments());
        response.put("runningAssignments", runningAssignments());
        return response;
    }

    private List<QueueListenerAssignment> localAssignments() {
        return localCoordinator == null
                ? Collections.emptyList()
                : localCoordinator.runningAssignments();
    }

    private List<QueueListenerAssignment> takeoverAssignments() {
        return takeoverCoordinator == null
                ? Collections.emptyList()
                : takeoverCoordinator.runningAssignments();
    }

    private List<QueueListenerAssignment> runningAssignments() {
        return Stream.concat(localAssignments().stream(), takeoverAssignments().stream())
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }
}
