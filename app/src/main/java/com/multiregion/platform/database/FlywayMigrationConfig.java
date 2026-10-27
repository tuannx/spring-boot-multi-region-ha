package com.multiregion.platform.database;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.topology.HomeRegion;
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
        Map<String, String> placeholders = RegionPlaceholders
                .forRegion(multiRegionConfig.awsRegion(), multiRegionConfig.shardWriterMode())
                .asMap();
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
            return forRegion(awsRegion, false);
        }

        static RegionPlaceholders forRegion(String awsRegion, boolean shardWriter) {
            if (awsRegion == null) {
                throw new IllegalArgumentException(
                        "AWS region must be set to resolve migration placeholders");
            }
            HomeRegion region = HomeRegion.require(awsRegion);
            String writerMode = shardWriter || region.homeWriter() ? "TRUE" : "FALSE";
            return new RegionPlaceholders(
                    region.databaseHost(),
                    region.peerHost(),
                    region.cpu(),
                    region.peerCpu(),
                    region.id(),
                    writerMode,
                    region.seedProductName(),
                    region.seedProductPrice());
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
