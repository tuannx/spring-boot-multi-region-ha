package com.example.openrtb.bidder.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetCacheTest {

  @Test
  void unknownCampaignHasNoBudget() {
    BudgetCache cache = new BudgetCache(Map.of("a", 10_000L));

    assertThat(cache.remaining("missing")).isZero();
  }

  @Test
  void applySpendLowersRemaining() {
    BudgetCache cache = new BudgetCache(Map.of("a", 10_000L));

    cache.applySpend("a", 4_000L);

    assertThat(cache.remaining("a")).isEqualTo(6_000L);
  }

  @Test
  void refreshRebuildsRemainingFromLedgerTotals() {
    BudgetCache cache = new BudgetCache(Map.of("a", 10_000L));
    cache.applySpend("a", 4_000L);

    cache.refresh(Map.of("a", 2_500L));

    assertThat(cache.remaining("a")).isEqualTo(7_500L);
  }

  @Test
  void refreshTreatsCampaignMissingFromLedgerAsUnspent() {
    BudgetCache cache = new BudgetCache(Map.of("a", 10_000L));
    cache.applySpend("a", 4_000L);

    cache.refresh(Map.of());

    assertThat(cache.remaining("a")).isEqualTo(10_000L);
  }
}
