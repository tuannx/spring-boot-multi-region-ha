package com.multiregion.platform.database;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${DB_USER:appuser}")
    private String dbUser;

    @Value("${DB_PASS:apppass}")
    private String dbPass;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${REGION_ROLE:primary}")
    private String regionRole;

    @Value("${ACTIVE_WRITER_DB_HOST:postgres-us}")
    private String activeWriterDbHost;

    @Value("${ACTIVE_WRITER_DB_PORT:5432}")
    private int activeWriterDbPort;

    @Value("${LOCAL_DB_HOST:}")
    private String localDbHost;

    @Value("${LOCAL_DB_PORT:5432}")
    private int localDbPort;

    @Value("${FAILOVER_WRITER_DB_HOST:postgres-eu}")
    private String failoverWriterDbHost;

    @Value("${FAILOVER_WRITER_DB_PORT:5432}")
    private int failoverWriterDbPort;

    @Value("${DB_NAME:appdb}")
    private String dbName;

    @Bean
    public DataSource writeDataSource() {
        String url = awsWrapperUrl(activeWriterDbHost, activeWriterDbPort);
        log.info("WritePool (initial active writer): region={} host={} url={}",
                awsRegion, activeWriterDbHost, url);
        return wrapperDataSource(url, "WritePool-" + awsRegion, 10, false);
    }

    @Bean
    public DataSource readDataSource() {
        String localReaderHost = "secondary".equalsIgnoreCase(regionRole)
                ? "postgres-eu"
                : "postgres-us";
        String url = awsWrapperUrl(localReaderHost, 5432);
        log.info("ReadPool (home region): region={} host={}", awsRegion, localReaderHost);
        return wrapperDataSource(url, "ReadPool-" + awsRegion, 20, true);
    }

    @Bean
    public DataSource primaryProbeDataSource() {
        String url = directPostgresUrl(activeWriterDbHost, activeWriterDbPort);
        log.info("PrimaryProbePool (bounded direct probe): region={} host={} url={}",
                awsRegion, activeWriterDbHost, url);

        HikariDataSource dataSource = directDataSource(
                url, "PrimaryProbePool-" + awsRegion, 2, 0);
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean
    public DataSource localAdminDataSource() {
        String host = resolvedLocalDbHost();
        String url = directPostgresUrl(host, localDbPort);
        log.info("LocalAdminPool (local control plane): region={} host={} url={}",
                awsRegion, host, url);

        HikariDataSource dataSource = directDataSource(
                url, "LocalAdminPool-" + awsRegion, 2, 0);
        dataSource.setReadOnly(false);
        return dataSource;
    }

    @Bean
    public DataSource promotedWriterDataSource() {
        String url = directPostgresUrl(failoverWriterDbHost, failoverWriterDbPort);
        log.info("PromotedWriterPool (global failover writer): region={} host={} url={}",
                awsRegion, failoverWriterDbHost, url);

        HikariDataSource dataSource = directDataSource(
                url, "PromotedWriterPool-" + awsRegion, 10, 2);
        dataSource.setReadOnly(false);
        return dataSource;
    }

    private HikariDataSource wrapperDataSource(
            String url,
            String poolName,
            int maximumPoolSize,
            boolean readOnly) {
        Properties properties = new Properties();
        properties.setProperty("user", dbUser);
        properties.setProperty("password", dbPass);
        properties.setProperty("wrapperPlugins", "failover2,dev");
        properties.setProperty("wrapperDialect", "pg");
        properties.setProperty("failoverHomeRegion", awsRegion);
        properties.setProperty("clusterInstanceHostPattern", "?:5432");
        properties.setProperty("clusterTopologyRefreshRateMs", "5000");
        properties.setProperty("failoverTimeoutMs", "5000");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setDataSourceProperties(properties);
        dataSource.setPoolName(poolName);
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(5000);
        dataSource.setIdleTimeout(30000);
        dataSource.setMaxLifetime(60000);
        dataSource.setConnectionTestQuery("SELECT 1");
        dataSource.setReadOnly(readOnly);
        return dataSource;
    }

    private HikariDataSource directDataSource(
            String url,
            String poolName,
            int maximumPoolSize,
            int minimumIdle) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(dbUser);
        dataSource.setPassword(dbPass);
        dataSource.setPoolName(poolName);
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setConnectionTimeout(5000);
        dataSource.setValidationTimeout(2000);
        dataSource.setIdleTimeout(30000);
        dataSource.setMaxLifetime(60000);
        dataSource.setConnectionTestQuery("SELECT 1");
        return dataSource;
    }

    private String awsWrapperUrl(String host, int port) {
        return "jdbc:aws-wrapper:postgresql://" + host + ":" + port + "/" + dbName;
    }

    private String directPostgresUrl(String host, int port) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + dbName
                + "?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true";
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
