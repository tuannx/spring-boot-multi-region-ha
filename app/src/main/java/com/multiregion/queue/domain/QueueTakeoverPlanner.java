package com.multiregion.queue.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueueTakeoverPlanner {

    public List<QueueListenerAssignment> plan(String localRegion, List<QueueRegionState> states) {
        Map<String, List<QueueRegionState>> byQueue = states.stream()
                .collect(Collectors.groupingBy(QueueRegionState::queueName));

        List<QueueListenerAssignment> assignments = new ArrayList<>();
        for (String queueName : byQueue.keySet().stream().sorted().toList()) {
            List<QueueRegionState> queueStates = byQueue.get(queueName);
            boolean localQueueIsUp = queueStates.stream()
                    .anyMatch(state -> state.region().equals(localRegion) && state.isUp());

            if (!localQueueIsUp) {
                continue;
            }

            queueStates.stream()
                    .filter(state -> !state.region().equals(localRegion))
                    .filter(QueueRegionState::isDown)
                    .sorted(Comparator.comparing(QueueRegionState::region))
                    .map(state -> new QueueListenerAssignment(queueName, state.region(), ListenerMode.TAKEOVER))
                    .forEach(assignments::add);
        }

        return assignments;
    }
}
