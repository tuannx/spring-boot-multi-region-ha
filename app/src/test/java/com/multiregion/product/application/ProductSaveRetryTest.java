package com.multiregion.product.application;

import com.multiregion.product.domain.Product;
import com.multiregion.product.port.ProductCatalog;
import com.multiregion.product.port.ProductDataRoute;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductSaveRetryTest {

    @Test
    void failedCreateClearsAGeneratedIdTheWriterDoesNotStore() {
        Catalog catalog = new Catalog();
        catalog.failNextCreate = true;
        ProductService service = service(catalog);
        Product product = new Product("drill-r1-w0-n000001", new BigDecimal("1.00"), null);

        assertThrows(DataAccessResourceFailureException.class, () -> service.save(product));

        assertNull(product.getId());
        service.save(product);
        assertEquals(List.of("drill-r1-w0-n000001"), catalog.storedNames());
    }

    @Test
    void failedCreateKeepsTheIdWhenTheWriterAlreadyStoredTheRow() {
        Catalog catalog = new Catalog();
        catalog.failNextCreate = true;
        catalog.storeBeforeFailure = true;
        ProductService service = service(catalog);
        Product product = new Product("drill-r1-w1-n000002", new BigDecimal("1.00"), "eu-west-1");

        assertThrows(DataAccessResourceFailureException.class, () -> service.save(product));

        assertEquals(1L, product.getId());
        service.save(product);
        assertEquals(List.of("drill-r1-w1-n000002"), catalog.storedNames());
    }

    @Test
    void enforcedOwnershipRejectsAForeignShardAndLeavesTheCatalogUntouched() {
        Catalog catalog = new Catalog();
        ProductService service = new ProductService(catalog, new DirectRoute(), () -> "eu-west-1", true);
        Product product = new Product("foreign-shard", new BigDecimal("1.00"), "us-east-1");

        ShardOwnershipConflict conflict = assertThrows(ShardOwnershipConflict.class, () -> service.save(product));

        assertEquals("eu-west-1", conflict.ownerRegion());
        assertEquals("us-east-1", conflict.requestedRegion());
        assertEquals(List.of(), catalog.storedNames());
    }

    @Test
    void enforcedOwnershipStampsAnEmptyRegionWithTheHomeWriter() {
        Catalog catalog = new Catalog();
        ProductService service = new ProductService(catalog, new DirectRoute(), () -> "ap-southeast-1", true);
        Product product = new Product("home-shard", new BigDecimal("1.00"), null);

        Product saved = service.save(product);

        assertEquals("ap-southeast-1", saved.getRegion());
        assertEquals(List.of("home-shard"), catalog.storedNames());
    }

    private static ProductService service(Catalog catalog) {
        return new ProductService(catalog, new DirectRoute(), () -> "eu-west-1", false);
    }

    private static final class DirectRoute implements ProductDataRoute {
        @Override
        public <T> T read(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T write(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public void write(Runnable operation) {
            operation.run();
        }
    }

    private static final class Catalog implements ProductCatalog {
        private final List<Product> rows = new ArrayList<>();
        private long nextId = 1;
        private boolean failNextCreate;
        private boolean storeBeforeFailure;

        @Override
        public List<Product> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<Product> findById(Long id) {
            return rows.stream().filter(row -> id.equals(row.getId())).findFirst();
        }

        @Override
        public <S extends Product> S save(S product) {
            if (product.getId() == null) {
                product.setId(nextId++);
                if (failNextCreate) {
                    failNextCreate = false;
                    if (storeBeforeFailure) {
                        rows.add(product);
                    }
                    throw new DataAccessResourceFailureException("commit failed");
                }
                rows.add(product);
                return product;
            }
            rows.removeIf(row -> product.getId().equals(row.getId()));
            rows.add(product);
            return product;
        }

        @Override
        public void deleteById(Long id) {
            rows.removeIf(row -> id.equals(row.getId()));
        }

        private List<String> storedNames() {
            return rows.stream().map(Product::getName).toList();
        }
    }
}
