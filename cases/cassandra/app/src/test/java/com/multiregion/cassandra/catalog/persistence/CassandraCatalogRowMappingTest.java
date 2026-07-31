package com.multiregion.cassandra.catalog.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.cassandra.core.mapping.CassandraMappingContext;

import static org.assertj.core.api.Assertions.assertThat;

class CassandraCatalogRowMappingTest {

    @Test
    void mapsCamelCasePropertiesToExplicitSnakeCaseSchemaColumns() {
        CassandraMappingContext context = new CassandraMappingContext();
        var entity = context.getRequiredPersistentEntity(CassandraCatalogRow.class);

        assertThat(entity.getRequiredPersistentProperty("originRegion").getColumnName().toString())
                .isEqualTo("origin_region");
        assertThat(entity.getRequiredPersistentProperty("createdAt").getColumnName().toString())
                .isEqualTo("created_at");
        assertThat(entity.getRequiredPersistentProperty("updatedAt").getColumnName().toString())
                .isEqualTo("updated_at");
    }
}
