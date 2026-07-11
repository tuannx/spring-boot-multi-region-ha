package com.multiregion.platform.failover.port;

import com.multiregion.platform.failover.domain.FailoverCheckResult;

public interface FailoverMonitor {

    FailoverCheckResult checkPrimaryHealth();
}
