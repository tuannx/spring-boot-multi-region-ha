package com.multiregion.queue.port;

import java.util.List;

public interface QueueCoordinationPolicy {

    long takeoverMaxDurationMs();

    List<String> names();

    List<String> regions();
}
