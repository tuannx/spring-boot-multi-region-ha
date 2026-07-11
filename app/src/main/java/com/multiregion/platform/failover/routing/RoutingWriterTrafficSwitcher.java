package com.multiregion.platform.failover.routing;

import com.multiregion.platform.routing.RoutingDataSource;
import com.multiregion.platform.failover.port.WriterTrafficSwitcher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RoutingWriterTrafficSwitcher implements WriterTrafficSwitcher {

    private final RoutingDataSource routingDataSource;
    private final String promotedWriterId;

    public RoutingWriterTrafficSwitcher(
            @Qualifier("regionalRoutingDataSource") RoutingDataSource routingDataSource,
            @Value("${FAILOVER_WRITER_DB_HOST:postgres-eu}") String promotedWriterId) {
        this.routingDataSource = routingDataSource;
        this.promotedWriterId = promotedWriterId;
    }

    @Override
    public void activatePromotedWriter() {
        routingDataSource.activatePromotedWriter();
    }

    @Override
    public boolean isPromotedWriterActive() {
        return routingDataSource.isPromotedWriterActive();
    }

    @Override
    public void restoreInitialWriter() {
        routingDataSource.restoreInitialWriter();
    }

    @Override
    public boolean isInitialWriterActive() {
        return routingDataSource.isInitialWriterActive();
    }

    @Override
    public String promotedWriterId() {
        return promotedWriterId;
    }
}
