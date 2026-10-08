package com.multiregion.elasticache;

import com.multiregion.elasticache.platform.config.RegionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RegionProperties.class)
public class ElasticacheMultiRegionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElasticacheMultiRegionApplication.class, args);
    }
}
