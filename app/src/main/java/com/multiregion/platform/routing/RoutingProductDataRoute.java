package com.multiregion.platform.routing;

import com.multiregion.product.port.ProductDataRoute;
import io.github.resilience4j.bulkhead.Bulkhead;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class RoutingProductDataRoute implements ProductDataRoute {

    private final Bulkhead writeBulkhead;

    public RoutingProductDataRoute(Bulkhead productWriteBulkhead) {
        this.writeBulkhead = productWriteBulkhead;
    }

    @Override
    public <T> T read(Supplier<T> operation) {
        return routed(RoutingDataSource.READER, operation);
    }

    @Override
    public <T> T write(Supplier<T> operation) {
        return writeBulkhead.executeSupplier(() -> routed(RoutingDataSource.WRITER, operation));
    }

    @Override
    public void write(Runnable operation) {
        writeBulkhead.executeRunnable(
                () -> RoutingDataSource.withRoute(RoutingDataSource.WRITER, operation));
    }

    private <T> T routed(String target, Supplier<T> operation) {
        return RoutingDataSource.withRoute(target, operation);
    }
}
