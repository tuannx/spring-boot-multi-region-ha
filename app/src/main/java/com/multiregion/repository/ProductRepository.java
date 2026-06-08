package com.multiregion.repository;

import com.multiregion.model.Product;
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
