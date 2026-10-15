package com.example.openrtb.loadgen;

import com.example.openrtb.loadgen.Stats.Category;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.ConcurrentHistogram;

/**
 * Writes the run result as JSON. The JSON is raw input for scripts/analyze.py, which computes the
 * benchmark tables and the failover RTO from the per-second timeline.
 */
final class Report {

  private Report() {}

  static void write(
      Path out, Options o, Stats s, long startEpochMs, long wallNanos, double requestBytesAvg)
      throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("startEpochMs", startEpochMs);
    root.put("wallSeconds", round3(wallNanos / 1e9));
    root.put(
        "options",
        obj(
            "target", o.target().toString(),
            "rps", o.rps(),
            "durationSec", o.durationSec(),
            "warmupSec", o.warmupSec(),
            "deadlineMs", o.deadlineMs(),
            "clientTimeoutMs", o.clientTimeoutMs(),
            "maxInFlight", o.maxInFlight(),
            "format", o.format().name(),
            "coldConnections", o.coldConnections(),
            "winRate", o.winRate(),
            "burlDuplicateRate", o.burlDuplicateRate(),
            "seed", o.seed()));
    root.put(
        "measured",
        obj(
            "requests", s.measuredTotal(),
            "bid", s.measuredCount(Category.BID),
            "nobid", s.measuredCount(Category.NOBID),
            "late", s.measuredCount(Category.LATE),
            "error", s.measuredCount(Category.ERROR),
            "dropped", s.dropped()));

    ConcurrentHistogram h = s.latencyUs();
    root.put(
        "latencyUs",
        obj(
            "mean", round3(h.getMean()),
            "p50", h.getValueAtPercentile(50.0),
            "p90", h.getValueAtPercentile(90.0),
            "p99", h.getValueAtPercentile(99.0),
            "p999", h.getValueAtPercentile(99.9),
            "max", h.getMaxValue()));

    root.put(
        "billing",
        obj(
            "burlSent", s.burlSent(),
            "burlAttempts", s.burlAttempts(),
            "burlRetried", s.burlRetried(),
            "burlOk", s.burlOk(),
            "burlErr", s.burlErr(),
            "dupSent", s.dupSent(),
            "dupOk", s.dupOk(),
            "distinctBilledBids", s.distinctBilledBids()));

    root.put(
        "payload",
        obj(
            "requestBytesAvg", round3(requestBytesAvg),
            "bidResponseBytesAvg", round3(s.averageBidResponseBytes())));
    root.put("generator", obj("maxDispatchLagUs", s.maxDispatchLagNanos() / 1_000L));

    List<Object> timeline = new ArrayList<>();
    int seconds = o.warmupSec() + o.durationSec();
    for (int sec = 0; sec < seconds; sec++) {
      timeline.add(
          obj(
              "sec", sec,
              "phase", sec < o.warmupSec() ? "warmup" : "measure",
              "total", s.timelineTotal(sec),
              "bid", s.timelineCount(Category.BID, sec),
              "nobid", s.timelineCount(Category.NOBID, sec),
              "late", s.timelineCount(Category.LATE, sec),
              "error", s.timelineCount(Category.ERROR, sec)));
    }
    root.put("timeline", timeline);

    StringBuilder sb = new StringBuilder(64 * 1024);
    writeJson(sb, root);
    sb.append('\n');
    Path abs = out.toAbsolutePath();
    Files.createDirectories(abs.getParent());
    Files.writeString(abs, sb.toString());

    System.out.printf(
        "requests=%d bid=%d nobid=%d late=%d error=%d dropped=%d | p50=%dus p99=%dus p999=%dus%n",
        s.measuredTotal(),
        s.measuredCount(Category.BID),
        s.measuredCount(Category.NOBID),
        s.measuredCount(Category.LATE),
        s.measuredCount(Category.ERROR),
        s.dropped(),
        h.getValueAtPercentile(50.0),
        h.getValueAtPercentile(99.0),
        h.getValueAtPercentile(99.9));
    System.out.printf(
        "billing: burl sent=%d ok=%d err=%d dup sent=%d | distinct billed bids=%d | report=%s%n",
        s.burlSent(), s.burlOk(), s.burlErr(), s.dupSent(), s.distinctBilledBids(), abs);
  }

  private static Map<String, Object> obj(Object... kv) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put((String) kv[i], kv[i + 1]);
    }
    return map;
  }

  private static double round3(double v) {
    return Math.round(v * 1000d) / 1000d;
  }

  private static void writeJson(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeJson(sb, e.getKey().toString());
        sb.append(':');
        writeJson(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      sb.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        writeJson(sb, list.get(i));
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
    }
  }
}
