package com.multiregion.platform.routing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

/** Composes physical pools behind the application's primary lazy data source. */
@Configuration
public class RoutingDataSources {

    @Bean(name = "regionalRoutingDataSource")
    public RoutingDataSource regionalRoutingDataSource(
            @Qualifier("writeDataSource") DataSource writeDataSource,
            @Qualifier("readDataSource") DataSource readDataSource,
            @Qualifier("promotedWriterDataSource") DataSource promotedWriterDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.setTargetDataSources(Map.of(
                RoutingDataSource.WRITER, writeDataSource,
                RoutingDataSource.READER, readDataSource,
                RoutingDataSource.PROMOTED_WRITER, promotedWriterDataSource));
        routing.afterPropertiesSet();
        return routing;
    }

    @Bean(name = "routingDataSource")
    @Primary
    public DataSource routingDataSource(
            @Qualifier("regionalRoutingDataSource") RoutingDataSource regionalRoutingDataSource) {
        return new LazyConnectionDataSourceProxy(regionalRoutingDataSource);
    }
}
