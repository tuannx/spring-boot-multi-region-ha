package com.multiregion.platform.config;

import com.multiregion.product.port.ProductRegionProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

@Component
public record MultiRegionConfig(PklApplicationConfig pkl, Environment environment)
        implements ProductRegionProvider {

    @Autowired
    public MultiRegionConfig(PklApplicationConfig pkl, Environment environment) {
        this.pkl = pkl;
        this.environment = environment;
    }

    MultiRegionConfig(PklApplicationConfig pkl) {
        this(pkl, new StandardEnvironment());
    }

    public String dbUser() {
        return stringProperty("DB_USER", pkl.getDB_USER());
    }

    public String dbPass() {
        return stringProperty("DB_PASS", pkl.getDB_PASS());
    }

    public String dbName() {
        return stringProperty("DB_NAME", pkl.getDB_NAME());
    }

    public String regionRole() {
        return stringProperty("REGION_ROLE", pkl.getREGION_ROLE());
    }

    public String awsRegion() {
        return stringProperty("AWS_REGION", pkl.getAWS_REGION());
    }

    @Override
    public String region() {
        return awsRegion();
    }

    public String failoverHomeRegion() {
        return stringProperty("FAILOVER_HOME_REGION", pkl.getFAILOVER_HOME_REGION());
    }

    public String activeHomeFailoverMode() {
        return stringProperty("ACTIVE_HOME_FAILOVER_MODE", pkl.getACTIVE_HOME_FAILOVER_MODE());
    }

    public String inactiveHomeFailoverMode() {
        return stringProperty("INACTIVE_HOME_FAILOVER_MODE", pkl.getINACTIVE_HOME_FAILOVER_MODE());
    }

    public String globalClusterPatterns() {
        return stringProperty("GLOBAL_CLUSTER_PATTERNS", pkl.getGLOBAL_CLUSTER_PATTERNS());
    }

    public String clusterInstancePattern() {
        return stringProperty("CLUSTER_INSTANCE_PATTERN", pkl.getCLUSTER_INSTANCE_PATTERN());
    }

    public String activeWriterDbHost() {
        return stringProperty("ACTIVE_WRITER_DB_HOST", pkl.getACTIVE_WRITER_DB_HOST());
    }

    public int activeWriterDbPort() {
        return intProperty("ACTIVE_WRITER_DB_PORT", pkl.getACTIVE_WRITER_DB_PORT());
    }

    public String localDbHost() {
        return stringProperty("LOCAL_DB_HOST", pkl.getLOCAL_DB_HOST());
    }

    public int localDbPort() {
        return intProperty("LOCAL_DB_PORT", pkl.getLOCAL_DB_PORT());
    }

    public String failoverWriterDbHost() {
        return stringProperty("FAILOVER_WRITER_DB_HOST", pkl.getFAILOVER_WRITER_DB_HOST());
    }

    public int failoverWriterDbPort() {
        return intProperty("FAILOVER_WRITER_DB_PORT", pkl.getFAILOVER_WRITER_DB_PORT());
    }

    public int failoverFailureThreshold() {
        return intProperty("FAILOVER_FAILURE_THRESHOLD", Math.toIntExact(pkl.getFAILOVER_FAILURE_THRESHOLD()));
    }

    public boolean allowUnfencedPromotion() {
        return environment.getProperty(
                "FAILOVER_ALLOW_UNFENCED_PROMOTION",
                Boolean.class,
                pkl.isFAILOVER_ALLOW_UNFENCED_PROMOTION());
    }

    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(regionRole());
    }

    public boolean isSecondary() {
        return "secondary".equalsIgnoreCase(regionRole());
    }

    private String stringProperty(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private int intProperty(String key, int defaultValue) {
        return environment.getProperty(key, Integer.class, defaultValue);
    }
}
