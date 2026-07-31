package com.multiregion.cassandra.catalog.application;

import com.multiregion.cassandra.catalog.domain.CatalogItem;
import com.multiregion.cassandra.catalog.port.CatalogStore;
import com.multiregion.cassandra.platform.config.RegionProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void createAssignsUuidRegionAndStableTimestampsBeforePersistence() {
        CatalogStore store = mock(CatalogStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CatalogService service = new CatalogService(
                store,
                new RegionProperties("eu-west-1", "eu-west-1"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        CatalogItem saved = service.create("Global catalog", new BigDecimal("42.50"));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.originRegion()).isEqualTo("eu-west-1");
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        verify(store).save(saved);
    }
}
