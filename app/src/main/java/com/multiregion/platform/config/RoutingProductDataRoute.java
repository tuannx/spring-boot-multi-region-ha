package com.multiregion.platform.config;

import com.multiregion.product.port.ProductDataRoute;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class RoutingProductDataRoute implements ProductDataRoute {

    @Override
    public <T> T read(Supplier<T> operation) {
        return routed("reader", operation);
    }

    @Override
    public <T> T write(Supplier<T> operation) {
        return routed("writer", operation);
    }

    @Override
    public void write(Runnable operation) {
        RoutingDataSource.routeTo("writer");
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
