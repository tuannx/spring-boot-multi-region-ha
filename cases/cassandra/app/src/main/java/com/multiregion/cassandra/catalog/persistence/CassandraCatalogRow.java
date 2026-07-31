package com.multiregion.cassandra.catalog.persistence;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;
import org.springframework.data.cassandra.core.mapping.Column;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("catalog_items")
public class CassandraCatalogRow {

    @PrimaryKey
    private UUID id;
    private String name;
    private BigDecimal price;
    @Column("origin_region")
    private String originRegion;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public CassandraCatalogRow() {
    }

    public CassandraCatalogRow(
            UUID id,
            String name,
            BigDecimal price,
            String originRegion,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.originRegion = originRegion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getOriginRegion() {
        return originRegion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
