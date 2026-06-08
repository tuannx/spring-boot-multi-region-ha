package com.multiregion.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * DataSource configuration using the AWS Advanced JDBC Wrapper.
 * <p>
 * Configures the Global Database (gdb) failover plugin for Aurora
 * multi-region support. The AWS JDBC Driver transparently handles
 * topology discovery and writer/reader routing.
 * <p>
 * Connection URL format:
 * {@code jdbc:aws-wrapper:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${DB_HOST:localhost}")
    private String dbHost;

    @Value("${DB_PORT:5432}")
    private int dbPort;

    @Value("${DB_NAME:appdb}")
    private String dbName;

    @Value("${DB_USER:appuser}")
    private String dbUser;

    @Value("${DB_PASS:apppass}")
    private String dbPass;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${FAILOVER_HOME_REGION:us-east-1}")
    private String failoverHomeRegion;

    @Value("${ACTIVE_HOME_FAILOVER_MODE:strict-writer}")
    private String activeHomeFailoverMode;

    @Value("${INACTIVE_HOME_FAILOVER_MODE:home-reader-or-writer}")
    private String inactiveHomeFailoverMode;

    @Value("${GLOBAL_CLUSTER_PATTERNS:}")
    private String globalClusterPatterns;

    @Value("${CLUSTER_INSTANCE_PATTERN:}")
    private String clusterInstancePattern;

    @Bean
    @Primary
    public DataSource dataSource() {
        String url = String.format("jdbc:aws-wrapper:postgresql://%s:%d/%s", dbHost, dbPort, dbName);

        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);

        // AWS JDBC Driver plugins
        props.setProperty("wrapperPlugins", "initialConnection,gdbFailover,efm2");

        // Aurora dialect for Global Database
        props.setProperty("wrapperDialect", "global-aurora-pg");

        // Failover home region (the region considered "home" for this cluster)
        props.setProperty("failoverHomeRegion", failoverHomeRegion);

        // Active home failover mode: strict-writer = only promote writer from home region
        props.setProperty("activeHomeFailoverMode", activeHomeFailoverMode);

        // Inactive home failover mode: allow reader from other regions to become writer
        props.setProperty("inactiveHomeFailoverMode", inactiveHomeFailoverMode);

        // Global cluster instance host patterns (comma-separated)
        if (globalClusterPatterns != null && !globalClusterPatterns.isEmpty()) {
            props.setProperty("globalClusterInstanceHostPatterns", globalClusterPatterns);
        }

        // Cluster instance host pattern (wildcard pattern for topology discovery)
        if (clusterInstancePattern != null && !clusterInstancePattern.isEmpty()) {
            props.setProperty("clusterInstanceHostPattern", clusterInstancePattern);
        }

        // Enable cluster topology refresh
        props.setProperty("clusterTopologyRefreshRateMs", "30000");

        // Connection pool settings (HikariCP)
        props.setProperty("maximumPoolSize", "10");
        props.setProperty("minimumIdle", "2");
        props.setProperty("connectionTimeout", "5000");
        props.setProperty("idleTimeout", "300000");
        props.setProperty("maxLifetime", "600000");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDataSourceProperties(props);
        ds.setPoolName("AuroraMultiRegionPool");
        ds.setConnectionTestQuery("SELECT 1");

        log.info("DataSource configured: url={}, region={}, homeRegion={}, plugins=initialConnection,gdbFailover,efm2",
                url, awsRegion, failoverHomeRegion);

        return ds;
    }
}
