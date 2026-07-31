package com.multiregion.cassandra.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CatalogItem(
        UUID id,
        String name,
        BigDecimal price,
        String originRegion,
        Instant createdAt,
        Instant updatedAt) {
}
