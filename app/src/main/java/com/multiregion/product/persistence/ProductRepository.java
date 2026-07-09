package com.multiregion.product.persistence;

import com.multiregion.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for Product entities.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByRegion(String region);
}
