package com.multiregion.product.application;

import com.multiregion.product.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProductRetryPolicyTest {

    @Test
    void writeOperationsRetainTheFailoverRetryPolicy() throws NoSuchMethodException {
        assertFailoverRetryPolicy(ProductService.class.getMethod("save", Product.class));
        assertFailoverRetryPolicy(ProductService.class.getMethod("deleteById", Long.class));
    }

    private static void assertFailoverRetryPolicy(Method method) {
        Retryable retryable = method.getAnnotation(Retryable.class);

        assertNotNull(retryable);
        assertArrayEquals(
            new Class<?>[] {
                DataAccessResourceFailureException.class,
                TransientDataAccessResourceException.class,
                SQLTransientConnectionException.class
            },
            retryable.includes()
        );
        assertEquals(4, retryable.maxRetries());
        assertEquals(500, retryable.delay());
        assertEquals(2, retryable.multiplier());
        assertEquals(4000, retryable.maxDelay());
    }

    @Test
    void saveCommitsInsideTheMethodSoAFailedCreateCanDropItsGeneratedId() throws NoSuchMethodException {
        Transactional transactional = ProductService.class
                .getMethod("save", Product.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.NOT_SUPPORTED, transactional.propagation());
    }
}
