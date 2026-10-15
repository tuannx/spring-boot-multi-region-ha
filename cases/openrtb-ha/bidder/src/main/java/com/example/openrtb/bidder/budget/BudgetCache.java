package com.example.openrtb.bidder.budget;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory view of remaining campaign budget, in micro-USD.
 *
 * <p>The bid path reads only this cache, so bidding never waits on the database. The view is
 * rebuilt from the durable billing ledger every refresh interval ({@link BudgetSync}). Between
 * refreshes, {@link #applySpend} lowers the local value optimistically. Bounded staleness is the
 * accepted trade-off: an instance can overspend by at most the spend it has not yet seen from other
 * instances within one refresh interval.
 */
public final class BudgetCache {

  private final Map<String, Long> totalMicros;
  private final Map<String, AtomicLong> remainingMicros = new ConcurrentHashMap<>();

  public BudgetCache(Map<String, Long> totalMicros) {
    this.totalMicros = Map.copyOf(totalMicros);
    this.totalMicros.forEach((id, total) -> remainingMicros.put(id, new AtomicLong(total)));
  }

  /** Remaining budget for a campaign, or 0 if the campaign is unknown. */
  public long remaining(String campaignId) {
    AtomicLong value = remainingMicros.get(campaignId);
    return value == null ? 0L : value.get();
  }

  /** Optimistic local decrement after a billing notice this instance accepted. */
  public void applySpend(String campaignId, long spendMicros) {
    AtomicLong value = remainingMicros.get(campaignId);
    if (value != null) {
      value.addAndGet(-spendMicros);
    }
  }

  /** Authoritative rebuild: remaining = total - spent-as-recorded-in-the-ledger. */
  public void refresh(Map<String, Long> spentByCampaign) {
    totalMicros.forEach(
        (id, total) ->
            remainingMicros.get(id).set(total - spentByCampaign.getOrDefault(id, 0L)));
  }
}
