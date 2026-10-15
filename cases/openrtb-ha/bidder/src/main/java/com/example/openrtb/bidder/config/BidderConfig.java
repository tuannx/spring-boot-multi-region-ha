package com.example.openrtb.bidder.config;

import com.example.openrtb.bidder.budget.BudgetCache;
import com.example.openrtb.bidder.config.BidderProperties.Campaign;
import com.example.openrtb.bidder.domain.BidEngine;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the domain objects from configuration. Fails at startup on duplicate campaign ids. */
@Configuration(proxyBeanMethods = false)
public class BidderConfig {

  private static final long MICROS_PER_USD = 1_000_000L;

  @Bean
  BudgetCache budgetCache(BidderProperties props) {
    Map<String, Long> totals = new HashMap<>();
    for (Campaign c : props.campaigns()) {
      long micros = Math.round(c.budgetUsd() * MICROS_PER_USD);
      if (totals.put(c.id(), micros) != null) {
        throw new IllegalStateException("duplicate campaign id: " + c.id());
      }
    }
    return new BudgetCache(totals);
  }

  @Bean
  BidEngine bidEngine(BidderProperties props, BudgetCache budget) {
    return new BidEngine(props.campaigns(), budget, props.bidExpSeconds());
  }
}
