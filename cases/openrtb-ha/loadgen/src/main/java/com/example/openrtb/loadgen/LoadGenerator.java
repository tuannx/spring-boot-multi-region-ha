package com.example.openrtb.loadgen;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed-rate OpenRTB load generator.
 *
 * <p>Request i is due at {@code start + i / rps}. Latency is measured from the due time, not from
 * the moment the request was actually sent. A stalled server therefore cannot hide its own stalls
 * by slowing the sender (coordinated omission). If the generator itself falls behind, the lag is
 * reported as {@code generator.maxDispatchLagUs} so the result can be checked for that.
 *
 * <pre>
 * java -jar loadgen.jar --target=http://localhost:8080 --rps=500 --duration=60 --warmup=10
 * </pre>
 */
public final class LoadGenerator {

  private static final long NANOS_PER_SEC = 1_000_000_000L;
  private static final int REQUEST_VARIANTS = 512;
  private static final String USAGE =
      "usage: --target=URL --rps=N --duration=S --warmup=S --deadline-ms=MS"
          + " --client-timeout-ms=MS --max-in-flight=N --format=proto|json --cold=true|false"
          + " --win-rate=0..1 --burl-dup-rate=0..1 --seed=N --out=FILE";

  private LoadGenerator() {}

  public static void main(String[] args) throws IOException {
    Options opts;
    try {
      opts = Options.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("error: " + e.getMessage());
      System.err.println(USAGE);
      System.exit(2);
      return;
    }
    run(opts);
  }

  private static void run(Options opts) throws IOException {
    // Pre-encoding is slow on a cold JVM. It must finish before the clock starts, or the first
    // requests are born late and their latency is charged to the server.
    RequestFactory factory = new RequestFactory(opts.deadlineMs(), opts.seed(), REQUEST_VARIANTS);
    long startEpochMs = System.currentTimeMillis();
    long startNanos = System.nanoTime();
    int timelineSeconds = opts.warmupSec() + opts.durationSec() + 2;
    Stats stats = new Stats(startNanos, opts.warmupSec() * NANOS_PER_SEC, timelineSeconds);
    Client client = new Client(opts, factory, stats);
    Semaphore inFlight = new Semaphore(opts.maxInFlight());
    long total = (long) opts.rps() * (opts.warmupSec() + opts.durationSec());

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long i = 0; i < total; i++) {
        long due = startNanos + i * NANOS_PER_SEC / opts.rps();
        waitUntil(due);
        stats.noteDispatchLag(System.nanoTime() - due);
        if (!inFlight.tryAcquire()) {
          stats.recordDropped(due, System.nanoTime());
          continue;
        }
        final long index = i;
        pool.execute(
            () -> {
              try {
                client.exchange(index, due);
              } finally {
                inFlight.release();
              }
            });
      }
    }

    long wallNanos = System.nanoTime() - startNanos;
    Report.write(
        opts.out(),
        opts,
        stats,
        startEpochMs,
        wallNanos,
        factory.averageBodyBytes(opts.format()));
  }

  private static void waitUntil(long dueNanos) {
    long remaining;
    while ((remaining = dueNanos - System.nanoTime()) > 0) {
      LockSupport.parkNanos(remaining);
    }
  }
}
