package com.example.openrtb.bidder.ledger;

import com.example.openrtb.bidder.budget.BudgetCache;
import com.example.openrtb.bidder.config.BidderProperties;
import com.example.openrtb.bidder.domain.BidEngine;
import com.example.openrtb.bidder.metrics.BidMetrics;
import java.util.HashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Settles one billing notice (burl).
 *
 * <p>Order matters: the durable insert runs first, and the local budget view is lowered only after
 * the insert succeeded. A duplicate notice therefore never reduces the budget a second time.
 */
@Service
public class BillingService {

  public enum Result {
    RECORDED,
    DUPLICATE,
    UNKNOWN_CAMPAIGN
  }

  private final BillingLedger ledger;
  private final BudgetCache budget;
  private final BidMetrics metrics;
  private final String instanceId;
  private final Map<String, Double> bidCpmByCampaign = new HashMap<>();

  public BillingService(
      BillingLedger ledger, BudgetCache budget, BidMetrics metrics, BidderProperties props) {
    this.ledger = ledger;
    this.budget = budget;
    this.metrics = metrics;
    this.instanceId = props.instanceId();
    props.campaigns().forEach(c -> bidCpmByCampaign.put(c.id(), c.cpm()));
  }

  /**
   * @param clearingCpm clearing price in USD CPM as reported by the exchange, or null if the
   *     {@code ${AUCTION_PRICE}} macro was not substituted. The value is capped at the CPM this
   *     bidder offered, so a misreported price cannot bill above the bid.
   * @throws DataAccessException if the ledger is unreachable; the caller should answer 503 so the
   *     exchange retries.
   */
  public Result settle(String bidId, String impId, String campaignId, Double clearingCpm) {
    Double bidCpm = bidCpmByCampaign.get(campaignId);
    if (bidCpm == null) {
      metrics.billingOutcome("unknown_campaign");
      return Result.UNKNOWN_CAMPAIGN;
    }
    double price = clearingCpm == null ? bidCpm : Math.max(0d, Math.min(clearingCpm, bidCpm));
    long spendMicros = BidEngine.spendMicrosPerImpression(price);

    boolean created;
    try {
      created = ledger.recordBilling(bidId, impId, campaignId, spendMicros, instanceId);
    } catch (DataAccessException e) {
      metrics.billingOutcome("error");
      throw e;
    }
    if (!created) {
      metrics.billingOutcome("duplicate");
      return Result.DUPLICATE;
    }
    budget.applySpend(campaignId, spendMicros);
    metrics.billingOutcome("recorded");
    return Result.RECORDED;
  }
}
