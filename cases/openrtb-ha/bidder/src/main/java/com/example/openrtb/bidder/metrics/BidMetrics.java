package com.example.openrtb.bidder.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Bidder metrics, exported through Actuator at /actuator/prometheus.
 *
 * <p>Tag values are fixed outcome names. Campaign, impression and bid ids are never used as tags,
 * so cardinality stays bounded.
 */
@Component
public class BidMetrics {

  private final MeterRegistry registry;
  private final Counter budgetRefreshFailures;
  private final Counter winNotices;
  private final Timer bidLatency;

  public BidMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.budgetRefreshFailures =
        Counter.builder("bidder.budget.refresh.failures")
            .description("Ledger reads that failed. The bidder kept serving the last good budget view.")
            .register(registry);
    this.winNotices =
        Counter.builder("bidder.notice.win")
            .description("Win notices (nurl) received. Informational, not billable.")
            .register(registry);
    this.bidLatency =
        Timer.builder("bidder.bid.latency")
            .description("Server-side time to answer POST /bid, including decision and encoding.")
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                Duration.ofMillis(25),
                Duration.ofMillis(50),
                Duration.ofMillis(100))
            .register(registry);
  }

  /** One of: bid, nobid, deadline, invalid. */
  public void bidOutcome(String outcome) {
    Counter.builder("bidder.bid.outcome").tag("outcome", outcome).register(registry).increment();
  }

  /** One of: recorded, duplicate, unknown_campaign, error. */
  public void billingOutcome(String outcome) {
    Counter.builder("bidder.billing.outcome")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  public void budgetRefreshFailure() {
    budgetRefreshFailures.increment();
  }

  public void winNotice() {
    winNotices.increment();
  }

  public void recordBidLatency(long nanos) {
    bidLatency.record(Duration.ofNanos(nanos));
  }
}
