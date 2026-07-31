package com.multiregion.cassandra.catalog.web;

import com.multiregion.cassandra.catalog.application.CatalogService;
import com.multiregion.cassandra.catalog.domain.CatalogItem;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog")
public class CatalogResource {

    private final CatalogService catalogService;

    public CatalogResource(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<CatalogItem> findAll() {
        return catalogService.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogItem> findById(@PathVariable UUID id) {
        return catalogService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CatalogItem> create(@Valid @RequestBody CatalogItemRequest request) {
        CatalogItem created = catalogService.create(request.name(), request.price());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CatalogItem> update(
            @PathVariable UUID id,
            @Valid @RequestBody CatalogItemRequest request) {
        return catalogService.update(id, request.name(), request.price())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return catalogService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
