package com.example.openrtb.loadgen;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Run configuration. Every flag has a default, so a bare run is a usable smoke test.
 *
 * <p>Flags use the form {@code --key=value}. Unknown flags are rejected rather than ignored, so a
 * typo cannot silently change a benchmark.
 */
record Options(
    URI target,
    int rps,
    int durationSec,
    int warmupSec,
    int deadlineMs,
    int clientTimeoutMs,
    int maxInFlight,
    Format format,
    boolean coldConnections,
    double winRate,
    double burlDuplicateRate,
    long seed,
    Path out) {

  enum Format {
    PROTO,
    JSON
  }

  private static final Set<String> KNOWN =
      Set.of(
          "target",
          "rps",
          "duration",
          "warmup",
          "deadline-ms",
          "client-timeout-ms",
          "max-in-flight",
          "format",
          "cold",
          "win-rate",
          "burl-dup-rate",
          "seed",
          "out");

  Options {
    require(rps > 0, "--rps must be > 0");
    require(durationSec > 0, "--duration must be > 0");
    require(warmupSec >= 0, "--warmup must be >= 0");
    require(deadlineMs > 0, "--deadline-ms must be > 0");
    require(clientTimeoutMs > 0, "--client-timeout-ms must be > 0");
    require(maxInFlight > 0, "--max-in-flight must be > 0");
    require(winRate >= 0 && winRate <= 1, "--win-rate must be in [0, 1]");
    require(
        burlDuplicateRate >= 0 && burlDuplicateRate <= 1,
        "--burl-dup-rate must be in [0, 1]");
  }

  static Options parse(String[] args) {
    Map<String, String> kv = new HashMap<>();
    for (String arg : args) {
      int eq = arg.indexOf('=');
      if (!arg.startsWith("--") || eq < 0) {
        throw new IllegalArgumentException("expected --key=value, got: " + arg);
      }
      kv.put(arg.substring(2, eq), arg.substring(eq + 1));
    }
    Set<String> unknown = new TreeSet<>(kv.keySet());
    unknown.removeAll(KNOWN);
    require(unknown.isEmpty(), "unknown flag(s): " + unknown);

    return new Options(
        URI.create(kv.getOrDefault("target", "http://localhost:8080")),
        intOf(kv, "rps", 500),
        intOf(kv, "duration", 30),
        intOf(kv, "warmup", 5),
        intOf(kv, "deadline-ms", 100),
        intOf(kv, "client-timeout-ms", 2000),
        intOf(kv, "max-in-flight", 2000),
        Format.valueOf(kv.getOrDefault("format", "proto").toUpperCase(Locale.ROOT)),
        Boolean.parseBoolean(kv.getOrDefault("cold", "false")),
        Double.parseDouble(kv.getOrDefault("win-rate", "0.5")),
        Double.parseDouble(kv.getOrDefault("burl-dup-rate", "0.05")),
        Long.parseLong(kv.getOrDefault("seed", "42")),
        Path.of(kv.getOrDefault("out", "report.json")));
  }

  private static int intOf(Map<String, String> kv, String key, int fallback) {
    return Integer.parseInt(kv.getOrDefault(key, Integer.toString(fallback)));
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}
