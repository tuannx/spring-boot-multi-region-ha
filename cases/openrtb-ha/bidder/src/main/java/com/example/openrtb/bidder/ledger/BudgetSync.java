package com.example.openrtb.bidder.ledger;

import com.example.openrtb.bidder.budget.BudgetCache;
import com.example.openrtb.bidder.metrics.BidMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically rebuilds the local budget view from the ledger. If the database is unreachable the
 * bidder keeps serving bids from the last good view (fail-open within the staleness bound) and
 * counts the failure, instead of failing the bid path.
 */
@Component
public class BudgetSync {

  private static final Logger log = LoggerFactory.getLogger(BudgetSync.class);

  private final BillingLedger ledger;
  private final BudgetCache budget;
  private final BidMetrics metrics;

  public BudgetSync(BillingLedger ledger, BudgetCache budget, BidMetrics metrics) {
    this.ledger = ledger;
    this.budget = budget;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${bidder.budget.refresh-ms:500}", initialDelay = 0)
  public void refresh() {
    try {
      budget.refresh(ledger.spentByCampaign());
    } catch (DataAccessException e) {
      metrics.budgetRefreshFailure();
      log.warn("budget refresh failed; serving from last good view: {}", e.getMessage());
    }
  }
}
