package com.multiregion.service;

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

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    public Product save(Product product) {
        if (product.getRegion() == null || product.getRegion().isEmpty()) {
            product.setRegion(awsRegion);
        }
        log.debug("Saving product: {} in region {}", product.getName(), product.getRegion());
        return productRepository.save(product);
    }

    public void deleteById(Long id) {
        productRepository.deleteById(id);
    }

    public List<Product> findByRegion(String region) {
        return productRepository.findByRegion(region);
    }
}
