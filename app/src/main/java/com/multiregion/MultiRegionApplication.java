package com.multiregion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MultiRegionApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiRegionApplication.class, args);
    }
}
