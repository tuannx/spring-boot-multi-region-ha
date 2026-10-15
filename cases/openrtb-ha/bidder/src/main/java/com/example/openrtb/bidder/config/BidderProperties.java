package com.example.openrtb.bidder.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bidder configuration. Values are bound from application.yml under the "bidder" prefix.
 *
 * <p>Money units: {@code cpm} and {@code budgetUsd} are in USD. Internally spend is tracked in
 * micro-USD (1 USD = 1_000_000) as {@code long} to avoid floating-point drift in the ledger.
 */
@ConfigurationProperties(prefix = "bidder")
public record BidderProperties(
    String instanceId,
    Deadline deadline,
    int bidExpSeconds,
    Budget budget,
    List<Campaign> campaigns) {

  public BidderProperties {
    if (instanceId == null || instanceId.isBlank()) {
      instanceId = "bidder-local";
    }
    if (deadline == null) {
      deadline = new Deadline(100, 1000, 10);
    }
    if (bidExpSeconds <= 0) {
      bidExpSeconds = 300;
    }
    if (budget == null) {
      budget = new Budget(500);
    }
    if (campaigns == null) {
      campaigns = List.of();
    }
  }

  /** Tmax handling: requests without tmax use defaultTmaxMs; values are clamped to max. */
  public record Deadline(int defaultTmaxMs, int maxTmaxMs, int safetyMarginMs) {}

  /** How often the in-memory budget view is rebuilt from the durable billing ledger. */
  public record Budget(long refreshMs) {}

  public record Campaign(
      String id, double cpm, double budgetUsd, String crid, String adm, List<String> adomain) {}
}
