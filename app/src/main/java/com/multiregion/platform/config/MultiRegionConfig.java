package com.multiregion.platform.config;

import org.springframework.stereotype.Component;

@Component
public record MultiRegionConfig(PklMultiRegionConfig pkl) {

    public String regionRole() {
        return pkl.getREGION_ROLE();
    }

    public String awsRegion() {
        return pkl.getAWS_REGION();
    }

    public String failoverHomeRegion() {
        return pkl.getFAILOVER_HOME_REGION();
    }

    public int failoverFailureThreshold() {
        return Math.toIntExact(pkl.getFAILOVER_FAILURE_THRESHOLD());
    }

    public boolean allowUnfencedPromotion() {
        return pkl.isFAILOVER_ALLOW_UNFENCED_PROMOTION();
    }

    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(regionRole());
    }

    public boolean isSecondary() {
        return "secondary".equalsIgnoreCase(regionRole());
    }
}
