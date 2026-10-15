package com.example.openrtb.loadgen;

import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.ConcurrentHistogram;

/**
 * Thread-safe results for one run.
 *
 * <p>Latency is measured from each request's due time (intended send time). Warm-up requests are
 * sent and appear in the timeline, but are excluded from the counters and the latency histogram.
 */
final class Stats {

  enum Category {
    /** 200 with a bid, on time. */
    BID,
    /** 204, on time. */
    NOBID,
    /** A bid or no-bid that arrived after the exchange deadline. The exchange would drop it. */
    LATE,
    /** Connection error, timeout, 4xx or 5xx, or a request dropped at the in-flight cap. */
    ERROR
  }

  private static final long NANOS_PER_SEC = 1_000_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;
  private static final long MAX_TRACKABLE_US = 3_600_000_000L;

  private final long startNanos;
  private final long warmupNanos;
  private final int timelineSeconds;

  private final ConcurrentHistogram latencyUs = new ConcurrentHistogram(MAX_TRACKABLE_US, 3);
  private final EnumMap<Category, LongAdder> measured = new EnumMap<>(Category.class);
  private final EnumMap<Category, AtomicLongArray> timeline = new EnumMap<>(Category.class);

  private final LongAdder burlSent = new LongAdder();
  private final LongAdder burlOk = new LongAdder();
  private final LongAdder burlErr = new LongAdder();
  private final LongAdder burlAttempts = new LongAdder();
  private final LongAdder burlRetried = new LongAdder();
  private final LongAdder dupSent = new LongAdder();
  private final LongAdder dupOk = new LongAdder();
  private final LongAdder dropped = new LongAdder();
  private final LongAdder bidResponses = new LongAdder();
  private final LongAdder bidResponseBytes = new LongAdder();
  private final AtomicLong maxDispatchLagNanos = new AtomicLong();
  private final Set<String> billedBids = ConcurrentHashMap.newKeySet();

  Stats(long startNanos, long warmupNanos, int timelineSeconds) {
    this.startNanos = startNanos;
    this.warmupNanos = warmupNanos;
    this.timelineSeconds = timelineSeconds;
    for (Category c : Category.values()) {
      measured.put(c, new LongAdder());
      timeline.put(c, new AtomicLongArray(timelineSeconds));
    }
  }

  void recordRequest(long intendedNanos, long nowNanos, Category category) {
    timeline.get(category).incrementAndGet(secondOf(intendedNanos));
    if (intendedNanos - startNanos < warmupNanos) {
      return;
    }
    measured.get(category).increment();
    long micros = Math.max(1L, (nowNanos - intendedNanos) / NANOS_PER_MICRO);
    latencyUs.recordValue(Math.min(micros, MAX_TRACKABLE_US));
  }

  void recordDropped(long intendedNanos, long nowNanos) {
    dropped.increment();
    recordRequest(intendedNanos, nowNanos, Category.ERROR);
  }

  /** Body size of a 200 bid response, in bytes. Used for the protobuf-vs-JSON payload comparison. */
  void recordBidResponseBytes(int bytes) {
    bidResponses.increment();
    bidResponseBytes.add(bytes);
  }

  double averageBidResponseBytes() {
    long n = bidResponses.sum();
    return n == 0 ? 0d : (double) bidResponseBytes.sum() / n;
  }

  void noteDispatchLag(long lagNanos) {
    maxDispatchLagNanos.accumulateAndGet(Math.max(0L, lagNanos), Math::max);
  }

  /**
   * One billing notice, after its retries. {@code ok} means the exchange got a 200 for it, which
   * is what a real exchange would count as delivered.
   */
  void recordBurl(String bidId, boolean ok, int attempts) {
    burlSent.increment();
    burlAttempts.add(attempts);
    if (attempts > 1) {
      burlRetried.increment();
    }
    if (ok) {
      burlOk.increment();
      billedBids.add(bidId);
    } else {
      burlErr.increment();
    }
  }

  void recordDuplicate(boolean ok) {
    dupSent.increment();
    if (ok) {
      dupOk.increment();
    }
  }

  long measuredCount(Category c) {
    return measured.get(c).sum();
  }

  long measuredTotal() {
    long sum = 0;
    for (Category c : Category.values()) {
      sum += measuredCount(c);
    }
    return sum;
  }

  long timelineCount(Category c, int second) {
    return timeline.get(c).get(second);
  }

  long timelineTotal(int second) {
    long sum = 0;
    for (Category c : Category.values()) {
      sum += timelineCount(c, second);
    }
    return sum;
  }

  ConcurrentHistogram latencyUs() {
    return latencyUs;
  }

  long burlSent() {
    return burlSent.sum();
  }

  long burlOk() {
    return burlOk.sum();
  }

  /** Notices still unacknowledged after all retries: the billing gap the run ends with. */
  long burlErr() {
    return burlErr.sum();
  }

  long burlAttempts() {
    return burlAttempts.sum();
  }

  long burlRetried() {
    return burlRetried.sum();
  }

  long dupSent() {
    return dupSent.sum();
  }

  long dupOk() {
    return dupOk.sum();
  }

  long dropped() {
    return dropped.sum();
  }

  long maxDispatchLagNanos() {
    return maxDispatchLagNanos.get();
  }

  int distinctBilledBids() {
    return billedBids.size();
  }

  private int secondOf(long intendedNanos) {
    long sec = Math.max(0L, (intendedNanos - startNanos) / NANOS_PER_SEC);
    return (int) Math.min(sec, timelineSeconds - 1);
  }
}
