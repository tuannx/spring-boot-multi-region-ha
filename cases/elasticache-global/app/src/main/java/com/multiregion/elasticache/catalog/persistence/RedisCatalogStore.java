package com.multiregion.elasticache.catalog.persistence;

import com.multiregion.elasticache.catalog.domain.CatalogItem;
import com.multiregion.elasticache.catalog.port.CatalogStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class RedisCatalogStore implements CatalogStore {

    private static final String ITEM_KEY_PREFIX = "catalog:item:";
    private static final String INDEX_KEY = "catalog:items";

    private final StringRedisTemplate redis;

    public RedisCatalogStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<CatalogItem> findAll() {
        Set<String> ids = redis.opsForSet().members(INDEX_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(id -> findById(UUID.fromString(id)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(CatalogItem::createdAt).thenComparing(item -> item.id().toString()))
                .toList();
    }

    @Override
    public Optional<CatalogItem> findById(UUID id) {
        Map<Object, Object> fields = redis.opsForHash().entries(itemKey(id));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CatalogItem(
                id,
                text(fields, "name"),
                new BigDecimal(text(fields, "price")),
                text(fields, "originRegion"),
                Instant.parse(text(fields, "createdAt")),
                Instant.parse(text(fields, "updatedAt"))));
    }

    @Override
    public CatalogItem save(CatalogItem item) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", item.name());
        fields.put("price", item.price().toPlainString());
        fields.put("originRegion", item.originRegion());
        fields.put("createdAt", item.createdAt().toString());
        fields.put("updatedAt", item.updatedAt().toString());
        redis.opsForHash().putAll(itemKey(item.id()), fields);
        redis.opsForSet().add(INDEX_KEY, item.id().toString());
        return item;
    }

    @Override
    public void deleteById(UUID id) {
        redis.delete(itemKey(id));
        redis.opsForSet().remove(INDEX_KEY, id.toString());
    }

    private static String itemKey(UUID id) {
        return ITEM_KEY_PREFIX + id;
    }

    private static String text(Map<Object, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null) {
            throw new IllegalStateException("Missing catalog field in Valkey hash: " + field);
        }
        return value.toString();
    }
}
