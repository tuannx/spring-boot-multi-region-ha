package com.multiregion.platform.config;

import com.multiregion.product.port.ProductRegionProvider;
import org.springframework.stereotype.Component;

@Component
public record MultiRegionConfig(PklApplicationConfig pkl) implements ProductRegionProvider {

    public String dbUser() {
        return pkl.getDB_USER();
    }

    public String dbPass() {
        return pkl.getDB_PASS();
    }

    public String dbName() {
        return pkl.getDB_NAME();
    }

    public String regionRole() {
        return pkl.getREGION_ROLE();
    }

    public String awsRegion() {
        return pkl.getAWS_REGION();
    }

    @Override
    public String region() {
        return awsRegion();
    }

    public String failoverHomeRegion() {
        return pkl.getFAILOVER_HOME_REGION();
    }

    public String activeHomeFailoverMode() {
        return pkl.getACTIVE_HOME_FAILOVER_MODE();
    }

    public String inactiveHomeFailoverMode() {
        return pkl.getINACTIVE_HOME_FAILOVER_MODE();
    }

    public String globalClusterPatterns() {
        return pkl.getGLOBAL_CLUSTER_PATTERNS();
    }

    public String clusterInstancePattern() {
        return pkl.getCLUSTER_INSTANCE_PATTERN();
    }

    public String activeWriterDbHost() {
        return pkl.getACTIVE_WRITER_DB_HOST();
    }

    public int activeWriterDbPort() {
        return pkl.getACTIVE_WRITER_DB_PORT();
    }

    public String localDbHost() {
        return pkl.getLOCAL_DB_HOST();
    }

    public int localDbPort() {
        return pkl.getLOCAL_DB_PORT();
    }

    public String failoverWriterDbHost() {
        return pkl.getFAILOVER_WRITER_DB_HOST();
    }

    public int failoverWriterDbPort() {
        return pkl.getFAILOVER_WRITER_DB_PORT();
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
