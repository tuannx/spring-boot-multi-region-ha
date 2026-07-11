package com.multiregion.product.persistence;

import com.multiregion.product.domain.Product;
import com.multiregion.product.port.ProductCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence adapter for the product catalog port.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, ProductCatalog {
}
