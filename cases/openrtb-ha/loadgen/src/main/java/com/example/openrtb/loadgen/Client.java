package com.example.openrtb.loadgen;

import com.example.openrtb.loadgen.Stats.Category;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One exchange per call: a bid request, then (for a winning bid) a billing notice, optionally
 * repeated to exercise idempotency.
 *
 * <p>Warm mode shares one HTTP/1.1 keep-alive client. Cold mode builds and closes a client per
 * request, so every request pays for a new TCP (and TLS, if enabled) connection. The per-request
 * client also adds its own setup cost, so compare cold and warm runs with that in mind.
 */
final class Client {

  private static final String BID_PATH = "/bid";
  private static final String PROTO_TYPE = "application/x-protobuf";
  private static final String JSON_TYPE = "application/json";
  /** Exchanges retry billing notices until acknowledged. Three attempts, with growing pauses. */
  private static final int MAX_BURL_ATTEMPTS = 3;
  private static final long BURL_BACKOFF_MS = 50L;

  private final Options opts;
  private final RequestFactory factory;
  private final Stats stats;
  private final HttpClient shared;
  private final Duration timeout;
  private final long deadlineNanos;

  Client(Options opts, RequestFactory factory, Stats stats) {
    this.opts = opts;
    this.factory = factory;
    this.stats = stats;
    this.shared = opts.coldConnections() ? null : newHttpClient();
    this.timeout = Duration.ofMillis(opts.clientTimeoutMs());
    this.deadlineNanos = opts.deadlineMs() * 1_000_000L;
  }

  void exchange(long index, long intendedNanos) {
    HttpRequest request =
        HttpRequest.newBuilder(opts.target().resolve(BID_PATH))
            .timeout(timeout)
            .header("Content-Type", opts.format() == Options.Format.PROTO ? PROTO_TYPE : JSON_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(factory.body(index, opts.format())))
            .build();

    HttpResponse<byte[]> response;
    try {
      response = send(request);
    } catch (IOException e) {
      stats.recordRequest(intendedNanos, System.nanoTime(), Category.ERROR);
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }

    long now = System.nanoTime();
    Category category = classify(response.statusCode(), now - intendedNanos);
    stats.recordRequest(intendedNanos, now, category);
    if (category == Category.BID) {
      stats.recordBidResponseBytes(response.body().length);
      maybeBill(response.body());
    }
  }

  private Category classify(int status, long latencyNanos) {
    if (status != 200 && status != 204) {
      return Category.ERROR;
    }
    if (latencyNanos > deadlineNanos) {
      return Category.LATE;
    }
    return status == 200 ? Category.BID : Category.NOBID;
  }

  private void maybeBill(byte[] responseBody) {
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    if (rnd.nextDouble() >= opts.winRate()) {
      return;
    }
    Optional<BidResponse.Bid> bid = firstBid(responseBody);
    if (bid.isEmpty()) {
      return;
    }
    // Simulated clearing price: between 50% and 100% of the bid CPM.
    double clearing = bid.get().getPrice() * (0.5 + 0.5 * rnd.nextDouble());
    URI burl =
        URI.create(
            bid.get()
                .getBurl()
                .replace("${AUCTION_PRICE}", String.format(Locale.ROOT, "%.2f", clearing)));

    int attempts = 0;
    boolean ok = false;
    while (!ok && attempts < MAX_BURL_ATTEMPTS) {
      attempts++;
      ok = get(burl) == 200;
      if (!ok && attempts < MAX_BURL_ATTEMPTS) {
        pause(BURL_BACKOFF_MS * attempts);
      }
    }
    stats.recordBurl(bid.get().getId(), ok, attempts);
    if (ok && rnd.nextDouble() < opts.burlDuplicateRate()) {
      stats.recordDuplicate(get(burl) == 200);
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Optional<BidResponse.Bid> firstBid(byte[] body) {
    try {
      BidResponse response = decode(body);
      if (response.getSeatbidCount() == 0 || response.getSeatbid(0).getBidCount() == 0) {
        return Optional.empty();
      }
      return Optional.of(response.getSeatbid(0).getBid(0));
    } catch (InvalidProtocolBufferException e) {
      return Optional.empty();
    }
  }

  private BidResponse decode(byte[] body) throws InvalidProtocolBufferException {
    if (opts.format() == Options.Format.PROTO) {
      return BidResponse.parseFrom(body);
    }
    BidResponse.Builder builder = BidResponse.newBuilder();
    JsonFormat.parser().ignoringUnknownFields().merge(new String(body, StandardCharsets.UTF_8), builder);
    return builder.build();
  }

  /** @return HTTP status, or -1 if the request failed at the transport level. */
  private int get(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
    try {
      return send(request).statusCode();
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
    if (shared != null) {
      return shared.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
    try (HttpClient client = newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private HttpClient newHttpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();
  }
}
