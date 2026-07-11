package com.multiregion.platform.failover.port;

/**
 * Switches application write traffic between the initial and promoted global writers.
 */
public interface WriterTrafficSwitcher {

    void activatePromotedWriter();

    boolean isPromotedWriterActive();

    void restoreInitialWriter();

    boolean isInitialWriterActive();

    String promotedWriterId();
}
