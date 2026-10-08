package com.multiregion.platform.database;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.database.FlywayMigrationConfig.RegionPlaceholders;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywayMigrationConfigTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    @Test
    void usRegionResolvesWriterSeedAndSelfFirstTopology() {
        RegionPlaceholders placeholders = RegionPlaceholders.forRegion("us-east-1");

        assertThat(placeholders.asMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "flywaySelfInstance", "postgres-us",
                "flywayPeerInstance", "postgres-eu",
                "flywaySelfCpu", "10",
                "flywayPeerCpu", "8",
                "flywayRegion", "us-east-1",
                "flywayWriterMode", "TRUE",
                "flywayRegionalProductName", "Regional Product US",
                "flywayRegionalProductPrice", "19.99"));
    }

    @Test
    void euRegionResolvesReaderSeedAndPeerWriter() {
        RegionPlaceholders placeholders = RegionPlaceholders.forRegion("eu-west-1");

        assertThat(placeholders.asMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "flywaySelfInstance", "postgres-eu",
                "flywayPeerInstance", "postgres-us",
                "flywaySelfCpu", "8",
                "flywayPeerCpu", "10",
                "flywayRegion", "eu-west-1",
                "flywayWriterMode", "FALSE",
                "flywayRegionalProductName", "Regional Product EU",
                "flywayRegionalProductPrice", "39.99"));
    }

    @Test
    void unknownRegionFailsClosedInsteadOfMigratingWithGuessedValues() {
        assertThatThrownBy(() -> RegionPlaceholders.forRegion("ap-south-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ap-south-1");
        assertThatThrownBy(() -> RegionPlaceholders.forRegion(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyV1PlaceholderIsProvidedForBothRegions() throws IOException {
        Set<String> tokens = placeholderTokens("classpath:db/migration/V1__aurora_topology_mock.sql");

        assertThat(tokens).isNotEmpty();
        assertThat(RegionPlaceholders.forRegion("us-east-1").asMap().keySet())
                .containsAll(tokens);
        assertThat(RegionPlaceholders.forRegion("eu-west-1").asMap().keySet())
                .containsAll(tokens);
        assertThat(placeholderTokens("classpath:db/migration/V2__align_products_id_with_jpa.sql"))
                .as("V2 converges fresh and baselined databases without region-varying values")
                .isEmpty();
    }

    @Test
    void flywayTargetsOnlyTheLocalAdminPool() throws NoSuchMethodException {
        assertThat(DatabaseConnections.class
                        .getMethod("localAdminDataSource")
                        .isAnnotationPresent(FlywayDataSource.class))
                .as("local admin pool must carry @FlywayDataSource")
                .isTrue();
        for (String pool : new String[] {
            "writeDataSource", "readDataSource", "primaryProbeDataSource", "promotedWriterDataSource"
        }) {
            assertThat(DatabaseConnections.class
                            .getMethod(pool)
                            .isAnnotationPresent(FlywayDataSource.class))
                    .as("%s must never be a Flyway target", pool)
                    .isFalse();
        }
    }

    @Test
    void euLocalAdminPoolPointsAtTheHomeDatabaseInsteadOfTheActiveWriter() {
        DatabaseConnections connections = new DatabaseConnections(
                "appuser",
                "apppass",
                "eu-west-1",
                "secondary",
                "postgres-us",
                5432,
                "postgres-eu",
                5432,
                "postgres-eu",
                5432,
                "appdb");

        DataSource localAdminDataSource = connections.localAdminDataSource();

        assertThat(((HikariDataSource) localAdminDataSource).getJdbcUrl())
                .contains("postgres-eu")
                .doesNotContain("postgres-us");
    }

    @Test
    void customizerAppliesRegionPlaceholders() {
        MultiRegionConfig multiRegionConfig = mock(MultiRegionConfig.class);
        when(multiRegionConfig.awsRegion()).thenReturn("eu-west-1");

        FlywayConfigurationCustomizer customizer =
                new FlywayMigrationConfig().flywayRegionPlaceholders(multiRegionConfig);
        FluentConfiguration configuration = Flyway.configure();
        customizer.customize(configuration);

        assertThat(configuration.getPlaceholders())
                .containsEntry("flywayWriterMode", "FALSE")
                .containsEntry("flywaySelfInstance", "postgres-eu")
                .hasSize(8);
    }

    private static Set<String> placeholderTokens(String location) throws IOException {
        var resource = new DefaultResourceLoader().getResource(location);
        String sql;
        try (var input = resource.getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> tokens = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }
}
