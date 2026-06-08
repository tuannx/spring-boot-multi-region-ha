package com.multiregion.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${DB_USER:appuser}")
    private String dbUser;

    @Value("${DB_PASS:apppass}")
    private String dbPass;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${REGION_ROLE:primary}")
    private String regionRole;

    @Bean
    public DataSource writeDataSource() {
        String url = "jdbc:aws-wrapper:postgresql://postgres-us:5432/appdb";
        log.info("WritePool (global writer): region={} url={}", awsRegion, url);

        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);
        props.setProperty("wrapperPlugins", "failover2,dev");
        props.setProperty("wrapperDialect", "pg");
        props.setProperty("failoverHomeRegion", awsRegion);
        props.setProperty("clusterInstanceHostPattern", "?:5432");
        props.setProperty("clusterTopologyRefreshRateMs", "5000");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDataSourceProperties(props);
        ds.setPoolName("WritePool-" + awsRegion);
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(5000);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(60000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    @Bean
    public DataSource readDataSource() {
        boolean isSecondary = "secondary".equalsIgnoreCase(regionRole);
        String h1 = isSecondary ? "postgres-eu" : "postgres-us";
        log.info("ReadPool (home region): region={} host={}", awsRegion, h1);
        String url = "jdbc:aws-wrapper:postgresql://" + h1 + ":5432/appdb";

        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);
        props.setProperty("wrapperPlugins", "failover2,dev");
        props.setProperty("wrapperDialect", "pg");
        props.setProperty("failoverHomeRegion", awsRegion);
        props.setProperty("clusterInstanceHostPattern", "?:5432");
        props.setProperty("clusterTopologyRefreshRateMs", "5000");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDataSourceProperties(props);
        ds.setPoolName("ReadPool-" + awsRegion);
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(5000);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(60000);
        ds.setConnectionTestQuery("SELECT 1");
        ds.setReadOnly(true);
        return ds;
    }

    @Bean
    @Primary
    public DataSource routingDataSource(
            @Qualifier("writeDataSource") DataSource writeDataSource,
            @Qualifier("readDataSource") DataSource readDataSource) {
        Map<Object, Object> targetSources = new HashMap<>();
        targetSources.put("writer", writeDataSource);
        targetSources.put("reader", readDataSource);
        RoutingDataSource routing = new RoutingDataSource();
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.setTargetDataSources(targetSources);
        return routing;
    }
}
