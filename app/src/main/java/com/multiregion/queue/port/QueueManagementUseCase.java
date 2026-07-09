package com.multiregion.queue.port;

import com.multiregion.queue.domain.QueueHealthStatus;

public interface QueueManagementUseCase {

    QueueSnapshot snapshot();

    QueueSnapshot updateStatus(
            String queueName,
            String region,
            QueueHealthStatus status,
            String reason);
}
