package com.multiregion.product.port;

import com.multiregion.product.domain.Product;

import java.util.List;
import java.util.Optional;

public interface ProductCatalog {

    List<Product> findAll();

    Optional<Product> findById(Long id);

    <S extends Product> S save(S product);

    void deleteById(Long id);
}
