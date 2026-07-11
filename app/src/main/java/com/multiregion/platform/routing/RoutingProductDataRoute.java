package com.multiregion.platform.routing;

import com.multiregion.product.port.ProductDataRoute;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class RoutingProductDataRoute implements ProductDataRoute {

    @Override
    public <T> T read(Supplier<T> operation) {
        return routed(RoutingDataSource.READER, operation);
    }

    @Override
    public <T> T write(Supplier<T> operation) {
        return routed(RoutingDataSource.WRITER, operation);
    }

    @Override
    public void write(Runnable operation) {
        RoutingDataSource.routeTo(RoutingDataSource.WRITER);
        try {
            operation.run();
        } finally {
            RoutingDataSource.clearRoute();
        }
    }

    private <T> T routed(String target, Supplier<T> operation) {
        RoutingDataSource.routeTo(target);
        try {
            return operation.get();
        } finally {
            RoutingDataSource.clearRoute();
        }
    }
}
