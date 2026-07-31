package com.multiregion.cassandra;

import com.multiregion.cassandra.platform.config.RegionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RegionProperties.class)
public class CassandraMultiRegionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CassandraMultiRegionApplication.class, args);
    }
}
