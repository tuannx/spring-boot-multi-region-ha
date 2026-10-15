package com.example.openrtb.bidder.domain;

import com.example.openrtb.bidder.config.BidderProperties.Deadline;

/**
 * Converts an OpenRTB {@code tmax} (milliseconds the exchange will wait) into the time budget this
 * bidder may spend. The safety margin keeps the response inside the exchange deadline after network
 * and serialization overhead that the bidder cannot measure.
 */
public final class DeadlineBudget {

  private DeadlineBudget() {}

  public static long budgetMillis(int tmaxMs, Deadline cfg) {
    int tmax = tmaxMs <= 0 ? cfg.defaultTmaxMs() : Math.min(tmaxMs, cfg.maxTmaxMs());
    return Math.max(1L, tmax - cfg.safetyMarginMs());
  }
}
