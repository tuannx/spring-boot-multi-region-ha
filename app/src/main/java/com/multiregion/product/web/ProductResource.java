package com.multiregion.product.web;

import com.multiregion.product.application.ProductService;
import com.multiregion.product.domain.Product;
import com.multiregion.product.persistence.ReadOnlyProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductResource {

    private final ProductService productService;
    private final ReadOnlyProductRepository readOnlyRepo;

    public ProductResource(ProductService productService, ReadOnlyProductRepository readOnlyRepo) {
        this.productService = productService;
        this.readOnlyRepo = readOnlyRepo;
    }

    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts() {
        // Reads from ReadPool (reader replica in other region)
        return ResponseEntity.ok(readOnlyRepo.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return productService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        Product created = productService.save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return productService.findById(id)
                .map(existing -> {
                    product.setId(id);
                    return ResponseEntity.ok(productService.save(product));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        if (productService.findById(id).isPresent()) {
            productService.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
