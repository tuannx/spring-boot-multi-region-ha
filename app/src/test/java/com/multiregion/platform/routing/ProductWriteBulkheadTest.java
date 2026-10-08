package com.multiregion.platform.routing;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductWriteBulkheadTest {

    @Test
    void writeShedsLoadWhenBulkheadSaturated() {
        Bulkhead bulkhead = singlePermitBulkhead();
        bulkhead.acquirePermission();
        try {
            RoutingProductDataRoute dataRoute = new RoutingProductDataRoute(bulkhead);

            assertThatThrownBy(() -> dataRoute.write(() -> "shed"))
                    .isInstanceOf(BulkheadFullException.class);
        } finally {
            bulkhead.releasePermission();
        }
    }

    @Test
    void voidWriteOverloadAlsoShedsWhenSaturated() {
        Bulkhead bulkhead = singlePermitBulkhead();
        bulkhead.acquirePermission();
        try {
            RoutingProductDataRoute dataRoute = new RoutingProductDataRoute(bulkhead);

            assertThatThrownBy(() -> dataRoute.write(() -> {}))
                    .isInstanceOf(BulkheadFullException.class);
        } finally {
            bulkhead.releasePermission();
        }
    }

    @Test
    void permitsReturnAfterFailingWrite() {
        RoutingProductDataRoute dataRoute =
                new RoutingProductDataRoute(singlePermitBulkhead());

        assertThatThrownBy(() -> dataRoute.write(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(dataRoute.write(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void readBypassesTheWriteBulkhead() {
        Bulkhead bulkhead = singlePermitBulkhead();
        bulkhead.acquirePermission();
        try {
            RoutingProductDataRoute dataRoute = new RoutingProductDataRoute(bulkhead);

            assertThat(dataRoute.read(() -> "reader")).isEqualTo("reader");
        } finally {
            bulkhead.releasePermission();
        }
    }

    private static Bulkhead singlePermitBulkhead() {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return Bulkhead.of("test-writes", config);
    }
}
