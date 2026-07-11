package com.multiregion.platform.failover.port;

import com.multiregion.platform.failover.domain.FailoverActivationResult;

public interface FailoverControl {

    FailoverActivationResult forceFailover();

    boolean isActivated();

    /**
     * Restores the application route when a previous local promotion survived an app restart.
     *
     * @return {@code true} when this process is active after reconciliation, otherwise standby
     */
    boolean reconcilePersistedState();
}
