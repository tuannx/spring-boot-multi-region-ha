package com.multiregion.cassandra.catalog.port;

import com.multiregion.cassandra.catalog.domain.CatalogItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogStore {

    List<CatalogItem> findAll();

    Optional<CatalogItem> findById(UUID id);

    CatalogItem save(CatalogItem item);

    void deleteById(UUID id);
}
