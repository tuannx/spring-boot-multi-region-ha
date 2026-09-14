package com.multiregion.platform.database;

import com.multiregion.platform.config.MultiRegionConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Creates the physical database pools. Runtime selection between those pools
 * belongs to the routing adapter, not this connection factory.
 */
@Configuration
public class DatabaseConnections {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnections.class);
    private static final String ADVANCED_JDBC_DRIVER = "software.amazon.jdbc.Driver";
    private static final String ADVANCED_JDBC_URL_PREFIX = "jdbc:aws-wrapper:";
    private static final String FAILOVER_PLUGINS = "failover2,dev";
    private static final String NO_PLUGINS = "";

    private final String dbUser;
    private final String dbPass;
    private final String awsRegion;
    private final String regionRole;
    private final String activeWriterDbHost;
    private final int activeWriterDbPort;
    private final String localDbHost;
    private final int localDbPort;
    private final String failoverWriterDbHost;
    private final int failoverWriterDbPort;
    private final String dbName;
    private final String failoverHomeRegion;
    private final String clusterInstancePattern;

    @Autowired
    public DatabaseConnections(MultiRegionConfig config) {
        this(
                config.dbUser(),
                config.dbPass(),
                config.awsRegion(),
                config.regionRole(),
                config.activeWriterDbHost(),
                config.activeWriterDbPort(),
                config.localDbHost(),
                config.localDbPort(),
                config.failoverWriterDbHost(),
                config.failoverWriterDbPort(),
                config.dbName(),
                config.failoverHomeRegion(),
                config.clusterInstancePattern());
    }

    DatabaseConnections(
            String dbUser,
            String dbPass,
            String awsRegion,
            String regionRole,
            String activeWriterDbHost,
            int activeWriterDbPort,
            String localDbHost,
            int localDbPort,
            String failoverWriterDbHost,
            int failoverWriterDbPort,
            String dbName) {
        this(
                dbUser,
                dbPass,
                awsRegion,
                regionRole,
                activeWriterDbHost,
                activeWriterDbPort,
                localDbHost,
                localDbPort,
                failoverWriterDbHost,
                failoverWriterDbPort,
                dbName,
                awsRegion,
                "?:5432");
    }

    private DatabaseConnections(
            String dbUser,
            String dbPass,
            String awsRegion,
            String regionRole,
            String activeWriterDbHost,
            int activeWriterDbPort,
            String localDbHost,
            int localDbPort,
            String failoverWriterDbHost,
            int failoverWriterDbPort,
            String dbName,
            String failoverHomeRegion,
            String clusterInstancePattern) {
        this.dbUser = dbUser;
        this.dbPass = dbPass;
        this.awsRegion = awsRegion;
        this.regionRole = regionRole;
        this.activeWriterDbHost = activeWriterDbHost;
        this.activeWriterDbPort = activeWriterDbPort;
        this.localDbHost = localDbHost;
        this.localDbPort = localDbPort;
        this.failoverWriterDbHost = failoverWriterDbHost;
        this.failoverWriterDbPort = failoverWriterDbPort;
        this.dbName = dbName;
        this.failoverHomeRegion = failoverHomeRegion;
        this.clusterInstancePattern = clusterInstancePattern;
    }

    @Bean
    public DataSource writeDataSource() {
        String url = awsWrapperUrl(activeWriterDbHost, activeWriterDbPort);
        log.info("WritePool (initial active writer): region={} host={} url={}",
                awsRegion, activeWriterDbHost, url);
        return wrapperDataSource(url, "WritePool-" + awsRegion, 10, false);
    }

    @Bean
    public DataSource readDataSource() {
        String localReaderHost = resolvedLocalDbHost();
        String url = awsWrapperUrl(localReaderHost, localDbPort);
        log.info("ReadPool (home region): region={} host={} url={}",
                awsRegion, localReaderHost, url);
        return wrapperDataSource(url, "ReadPool-" + awsRegion, 20, true);
    }

    @Bean
    public DataSource primaryProbeDataSource() {
        String url = advancedJdbcUrl(activeWriterDbHost, activeWriterDbPort);
        log.info("PrimaryProbePool (bounded wrapper probe): region={} host={} url={}",
                awsRegion, activeWriterDbHost, url);
        return advancedJdbcDataSource(
                url, "PrimaryProbePool-" + awsRegion, 2, 0, true, NO_PLUGINS);
    }

    @Bean
    public DataSource localAdminDataSource() {
        String host = resolvedLocalDbHost();
        String url = advancedJdbcUrl(host, localDbPort);
        log.info("LocalAdminPool (wrapper-backed local control plane): region={} host={} url={}",
                awsRegion, host, url);
        return advancedJdbcDataSource(
                url, "LocalAdminPool-" + awsRegion, 2, 0, false, NO_PLUGINS);
    }

    @Bean
    public DataSource promotedWriterDataSource() {
        String url = advancedJdbcUrl(failoverWriterDbHost, failoverWriterDbPort);
        log.info("PromotedWriterPool (wrapper-backed global failover writer): region={} host={} url={}",
                awsRegion, failoverWriterDbHost, url);
        return advancedJdbcDataSource(
                url, "PromotedWriterPool-" + awsRegion, 10, 2, false, NO_PLUGINS);
    }

    private HikariDataSource wrapperDataSource(
            String url,
            String poolName,
            int maximumPoolSize,
            boolean readOnly) {
        return advancedJdbcDataSource(
                url, poolName, maximumPoolSize, 2, readOnly, FAILOVER_PLUGINS);
    }

    private HikariDataSource advancedJdbcDataSource(
            String url,
            String poolName,
            int maximumPoolSize,
            int minimumIdle,
            boolean readOnly,
            String plugins) {
        requireAdvancedJdbcUrl(url);

        Properties properties = new Properties();
        properties.setProperty("user", dbUser);
        properties.setProperty("password", dbPass);
        properties.setProperty("wrapperPlugins", plugins);
        properties.setProperty("wrapperDialect", "pg");
        properties.setProperty("failoverHomeRegion", failoverHomeRegion);
        properties.setProperty("clusterInstanceHostPattern", clusterInstancePattern);
        properties.setProperty("clusterTopologyRefreshRateMs", "5000");
        properties.setProperty("failoverTimeoutMs", "5000");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(ADVANCED_JDBC_DRIVER);
        dataSource.setJdbcUrl(url);
        dataSource.setDataSourceProperties(properties);
        dataSource.setPoolName(poolName);
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setConnectionTimeout(5000);
        dataSource.setValidationTimeout(2000);
        dataSource.setIdleTimeout(30000);
        dataSource.setMaxLifetime(60000);
        dataSource.setConnectionTestQuery("SELECT 1");
        dataSource.setReadOnly(readOnly);
        return dataSource;
    }

    private String awsWrapperUrl(String host, int port) {
        return advancedJdbcUrl(host, port);
    }

    private String advancedJdbcUrl(String host, int port) {
        return ADVANCED_JDBC_URL_PREFIX + "postgresql://" + host + ":" + port + "/" + dbName;
    }

    private void requireAdvancedJdbcUrl(String url) {
        if (!url.startsWith(ADVANCED_JDBC_URL_PREFIX)) {
            throw new IllegalArgumentException(
                    "All application database pools must use the AWS Advanced JDBC Wrapper: " + url);
        }
    }

    private String resolvedLocalDbHost() {
        return localDbHost == null || localDbHost.isBlank()
                ? defaultLocalDbHost()
                : localDbHost.trim();
    }

    private String defaultLocalDbHost() {
        if ("eu-west-1".equalsIgnoreCase(awsRegion)) {
            return "postgres-eu";
        }
        if ("us-east-1".equalsIgnoreCase(awsRegion)) {
            return "postgres-us";
        }
        return "secondary".equalsIgnoreCase(regionRole) ? "postgres-eu" : "postgres-us";
    }
}
