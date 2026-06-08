package com.multiregion.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * DataSource configuration.
 * <p>
 * For local Docker: uses plain PostgreSQL driver (the AWS JDBC Wrapper's
 * topology system requires real RDS host patterns).
 * <p>
 * For production AWS deployment: uncomment the AwsJdbcWrapper bean
 * to enable GDB failover with globalClusterInstanceHostPatterns.
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

    @Bean
    @Primary
    public DataSource dataSource() {
        String url = String.format("jdbc:postgresql://%s:%d/%s", dbHost, dbPort, dbName);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(dbUser);
        ds.setPassword(dbPass);
        ds.setPoolName("MultiRegionPool");
        ds.setConnectionTestQuery("SELECT 1");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(5000);
        ds.setIdleTimeout(300000);
        ds.setMaxLifetime(600000);

        log.info("DataSource: url={}, region={}, pool={}", url, awsRegion, ds.getPoolName());
        return ds;
    }

    /**
     * Alternate DataSource for production AWS deployment with Aurora Global Database.
     * <p>
     * To use: switch @Primary to this bean and configure:
     * <pre>
     * wrapperPlugins=initialConnection,gdbFailover,efm2
     * failoverHomeRegion=us-east-1
     * wrapperDialect=global-aurora-pg
     * globalClusterInstanceHostPatterns=[us-east-1]?.XXXX.us-east-1.rds.amazonaws.com,[eu-west-1]?.YYYY.eu-west-1.rds.amazonaws.com
     * </pre>
     */
    // @Bean
    // @Primary
    // @Profile("aws")
    public DataSource awsDataSource() {
        String url = String.format("jdbc:aws-wrapper:postgresql://%s:%d/%s", dbHost, dbPort, dbName);

        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);
        props.setProperty("wrapperPlugins", "initialConnection,gdbFailover,efm2");
        props.setProperty("wrapperDialect", "global-aurora-pg");
        props.setProperty("failoverHomeRegion", awsRegion);
        props.setProperty("activeHomeFailoverMode", "strict-writer");
        props.setProperty("inactiveHomeFailoverMode", "home-reader-or-writer");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDataSourceProperties(props);
        ds.setPoolName("AwsAuroraPool");
        return ds;
    }
}
