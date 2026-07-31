package com.multiregion.cassandra.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.region")
public record RegionProperties(
        @NotBlank String name,
        @NotBlank String localDatacenter) {
}
