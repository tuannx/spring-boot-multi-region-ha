package com.example.openrtb.bidder.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.openrtb.bidder.config.BidderProperties.Deadline;
import org.junit.jupiter.api.Test;

class DeadlineBudgetTest {

  private static final Deadline CFG = new Deadline(100, 1000, 10);

  @Test
  void missingTmaxUsesDefaultMinusSafetyMargin() {
    assertThat(DeadlineBudget.budgetMillis(0, CFG)).isEqualTo(90);
  }

  @Test
  void tmaxBelowLimitIsUsedMinusSafetyMargin() {
    assertThat(DeadlineBudget.budgetMillis(200, CFG)).isEqualTo(190);
  }

  @Test
  void tmaxAboveLimitIsClampedToMax() {
    assertThat(DeadlineBudget.budgetMillis(5_000, CFG)).isEqualTo(990);
  }

  @Test
  void budgetNeverDropsBelowOneMillisecond() {
    assertThat(DeadlineBudget.budgetMillis(5, CFG)).isEqualTo(1);
  }
}
