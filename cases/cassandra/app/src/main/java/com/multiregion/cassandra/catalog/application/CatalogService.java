package com.multiregion.cassandra.catalog.application;

import com.multiregion.cassandra.catalog.domain.CatalogItem;
import com.multiregion.cassandra.catalog.port.CatalogStore;
import com.multiregion.cassandra.platform.config.RegionProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CatalogService {

    private final CatalogStore catalogStore;
    private final RegionProperties region;
    private final Clock clock;

    public CatalogService(CatalogStore catalogStore, RegionProperties region, Clock clock) {
        this.catalogStore = catalogStore;
        this.region = region;
        this.clock = clock;
    }

    public List<CatalogItem> findAll() {
        return catalogStore.findAll();
    }

    public Optional<CatalogItem> findById(UUID id) {
        return catalogStore.findById(id);
    }

    public CatalogItem create(String name, BigDecimal price) {
        Instant now = clock.instant();
        CatalogItem item = new CatalogItem(
                UUID.randomUUID(),
                name,
                price,
                region.name(),
                now,
                now);
        return catalogStore.save(item);
    }

    public Optional<CatalogItem> update(UUID id, String name, BigDecimal price) {
        return catalogStore.findById(id)
                .map(existing -> catalogStore.save(new CatalogItem(
                        existing.id(),
                        name,
                        price,
                        existing.originRegion(),
                        existing.createdAt(),
                        clock.instant())));
    }

    public boolean delete(UUID id) {
        if (catalogStore.findById(id).isEmpty()) {
            return false;
        }
        catalogStore.deleteById(id);
        return true;
    }
}
