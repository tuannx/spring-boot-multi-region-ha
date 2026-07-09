package com.multiregion.queue.port;

import com.multiregion.queue.domain.QueueHealthStatus;
import com.multiregion.queue.domain.QueueRegionState;

import java.util.List;

public interface QueueRegionStateStore {

    List<QueueRegionState> findAll();

    void updateStatus(String queueName, String region, QueueHealthStatus status, String reason);
}
