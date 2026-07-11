package com.multiregion.platform.failover.port;

import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.TopologyInstance;

import java.util.List;

public interface AuroraTopologyGateway {

    PrimaryProbeResult probePrimary();

    boolean isDatabaseConnected();

    String currentWriter();

    List<TopologyInstance> topology();
}
