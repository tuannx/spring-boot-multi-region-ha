package com.example.openrtb.bidder;

import com.example.openrtb.bidder.config.BidderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan(basePackageClasses = BidderProperties.class)
public class BidderApplication {

  public static void main(String[] args) {
    SpringApplication.run(BidderApplication.class, args);
  }
}
