package com.multiregion.service;

import com.multiregion.config.RoutingDataSource;
import com.multiregion.model.Product;
import com.multiregion.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for product CRUD operations.
 * <p>
 * Write operations ({@link #save(Product)}, {@link #deleteById(Long)}) are
 * annotated with {@code @Retryable} to handle transient failures during
 * HA/DR failover:
 * <ul>
 *   <li>When the writer node is killed, the failover2 plugin detects the
 *       broken connection and reconnects to the new writer (~3-10s).</li>
 *   <li>During this window, write ops throw connection errors.</li>
 *   <li>Retry with exponential backoff covers the window transparently.</li>
 * </ul>
 * <p>
 * Read operations are NOT retried because the ReadPool connects to the
 * home-region reader, which is independent of the writer failover.
 */
@Service
@Transactional
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    @Autowired
    private ProductRepository productRepository;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        RoutingDataSource.routeTo("reader");
        try {
            return productRepository.findAll();
        } finally {
            RoutingDataSource.clearRoute();
        }
    }

    @Transactional(readOnly = true)
    public Optional<Product> findById(Long id) {
        RoutingDataSource.routeTo("reader");
        try {
            return productRepository.findById(id);
        } finally {
            RoutingDataSource.clearRoute();
        }
    }

    /**
     * Save a product with retry for HA/DR failover resilience.
     * <p>
     * Retries up to 5 times with exponential backoff (500ms → 1s → 2s → 4s → 4s)
     * when the underlying connection fails during writer failover.
     * Total max wait: ~11.5s, covering typical failover windows (3-10s).
     */
    @Retryable(
        retryFor = {
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            SQLTransientConnectionException.class
        },
        maxAttempts = 5,
        backoff = @Backoff(delay = 500, multiplier = 2, maxDelay = 4000)
    )
    public Product save(Product product) {
        RoutingDataSource.routeTo("writer");
        try {
            if (product.getRegion() == null || product.getRegion().isEmpty()) {
                product.setRegion(awsRegion);
            }
            log.debug("Saving product: {} in region {}", product.getName(), product.getRegion());
            return productRepository.save(product);
        } finally {
            RoutingDataSource.clearRoute();
        }
    }

    /**
     * Delete a product with retry for HA/DR failover resilience.
     * Same retry policy as {@link #save(Product)}.
     */
    @Retryable(
        retryFor = {
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            SQLTransientConnectionException.class
        },
        maxAttempts = 5,
        backoff = @Backoff(delay = 500, multiplier = 2, maxDelay = 4000)
    )
    public void deleteById(Long id) {
        RoutingDataSource.routeTo("writer");
        try {
            productRepository.deleteById(id);
        } finally {
            RoutingDataSource.clearRoute();
        }
    }

    @Transactional(readOnly = true)
    public List<Product> findByRegion(String region) {
        RoutingDataSource.routeTo("reader");
        try {
            return productRepository.findByRegion(region);
        } finally {
            RoutingDataSource.clearRoute();
        }
    }
}
