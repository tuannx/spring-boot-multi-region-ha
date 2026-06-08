package com.multiregion.service;

import com.multiregion.config.RoutingDataSource;
import com.multiregion.model.Product;
import com.multiregion.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Business logic for product CRUD operations.
 * Region-aware: automatically tags products with the deploying region.
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
