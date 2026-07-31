package com.multiregion.cassandra.catalog.persistence;

import com.multiregion.cassandra.catalog.domain.CatalogItem;
import com.multiregion.cassandra.catalog.port.CatalogStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SpringDataCassandraCatalogStore implements CatalogStore {

    private final CassandraCatalogRepository repository;

    public SpringDataCassandraCatalogStore(CassandraCatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CatalogItem> findAll() {
        return repository.findAll().stream()
                .map(SpringDataCassandraCatalogStore::toDomain)
                .toList();
    }

    @Override
    public Optional<CatalogItem> findById(UUID id) {
        return repository.findById(id).map(SpringDataCassandraCatalogStore::toDomain);
    }

    @Override
    public CatalogItem save(CatalogItem item) {
        return toDomain(repository.save(toRow(item)));
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private static CassandraCatalogRow toRow(CatalogItem item) {
        return new CassandraCatalogRow(
                item.id(),
                item.name(),
                item.price(),
                item.originRegion(),
                item.createdAt(),
                item.updatedAt());
    }

    private static CatalogItem toDomain(CassandraCatalogRow row) {
        return new CatalogItem(
                row.getId(),
                row.getName(),
                row.getPrice(),
                row.getOriginRegion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
