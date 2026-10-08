package com.multiregion.platform.database;

import com.multiregion.platform.config.MultiRegionConfig;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Resolves Flyway's region-varying V1 placeholders from {@code AWS_REGION}.
 * <p>
 * DataSource selection needs no customizer: {@link DatabaseConnections}
 * marks the local admin pool with {@code @FlywayDataSource}, so each region
 * migrates its own home database instead of the global writer. Unknown regions
 * fail fast instead of migrating with guessed values.
 */
@Configuration
public class FlywayMigrationConfig {

    @Bean
    public FlywayConfigurationCustomizer flywayRegionPlaceholders(
            MultiRegionConfig multiRegionConfig) {
        Map<String, String> placeholders =
                RegionPlaceholders.forRegion(multiRegionConfig.awsRegion()).asMap();
        return configuration -> configuration.placeholders(placeholders);
    }

    /** Region-varying values for the V1 migration placeholders. */
    record RegionPlaceholders(
            String selfInstance,
            String peerInstance,
            String selfCpu,
            String peerCpu,
            String region,
            String writerMode,
            String regionalProductName,
            String regionalProductPrice) {

        static RegionPlaceholders forRegion(String awsRegion) {
            if (awsRegion == null) {
                throw new IllegalArgumentException(
                        "AWS region must be set to resolve migration placeholders");
            }
            return switch (awsRegion) {
                case "us-east-1" -> new RegionPlaceholders(
                        "postgres-us", "postgres-eu", "10", "8",
                        "us-east-1", "TRUE", "Regional Product US", "19.99");
                case "eu-west-1" -> new RegionPlaceholders(
                        "postgres-eu", "postgres-us", "8", "10",
                        "eu-west-1", "FALSE", "Regional Product EU", "39.99");
                default -> throw new IllegalArgumentException(
                        "Unsupported AWS region for database migration: " + awsRegion);
            };
        }

        Map<String, String> asMap() {
            return Map.of(
                    "flywaySelfInstance", selfInstance,
                    "flywayPeerInstance", peerInstance,
                    "flywaySelfCpu", selfCpu,
                    "flywayPeerCpu", peerCpu,
                    "flywayRegion", region,
                    "flywayWriterMode", writerMode,
                    "flywayRegionalProductName", regionalProductName,
                    "flywayRegionalProductPrice", regionalProductPrice);
        }
    }
}
