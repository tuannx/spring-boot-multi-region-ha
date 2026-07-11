package com.multiregion.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record MultiRegionConfig(
        @Value("${REGION_ROLE:primary}") String regionRole,
        @Value("${AWS_REGION:us-east-1}") String awsRegion,
        @Value("${FAILOVER_HOME_REGION:us-east-1}") String failoverHomeRegion
) {

    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(regionRole);
    }

    public boolean isSecondary() {
        return "secondary".equalsIgnoreCase(regionRole);
    }
}
