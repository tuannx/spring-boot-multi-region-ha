package com.multiregion.platform.failover.port;

public interface FailoverPromotionGateway {

    void enableLocalWriterMode();

    boolean isLocalWriter();

    String localWriterId();
}
