package com.multiregion.queue.port;

import com.multiregion.queue.domain.QueueListenerAssignment;

public interface QueueListenerProvisioner {

    QueueListenerContainer create(QueueListenerAssignment assignment);
}
