package com.example.openrtb.bidder.api;

import com.example.openrtb.bidder.config.BidderProperties;
import com.example.openrtb.bidder.domain.BidEngine;
import com.example.openrtb.bidder.domain.DeadlineBudget;
import com.example.openrtb.bidder.metrics.BidMetrics;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.iabtechlab.openrtb.v2.OpenRtb.BidRequest;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenRTB 2.6 bid endpoint.
 *
 * <p>Protobuf ({@code application/x-protobuf}) is the fast path. JSON ({@code application/json}) is
 * accepted for interoperability and for the benchmark comparison. The response uses the same
 * encoding as the request.
 *
 * <p>Status codes: 200 with a BidResponse; 204 for no-bid, or when the decision finished after the
 * exchange deadline; 400 when the body does not parse.
 */
@RestController
public class BidController {

  private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
  private static final long NANOS_PER_MILLI = 1_000_000L;

  private final BidEngine engine;
  private final BidderProperties props;
  private final BidMetrics metrics;
  private final String burlBase;
  private final String nurlBase;

  public BidController(
      BidEngine engine,
      BidderProperties props,
      BidMetrics metrics,
      @Value("${bidder.public-base-url}") String publicBaseUrl) {
    this.engine = engine;
    this.props = props;
    this.metrics = metrics;
    this.burlBase = publicBaseUrl + "/burl";
    this.nurlBase = publicBaseUrl + "/nurl";
  }

  @PostMapping(value = "/bid", consumes = "application/x-protobuf")
  public ResponseEntity<byte[]> bidProtobuf(@RequestBody byte[] body) {
    return answer(body, true);
  }

  @PostMapping(value = "/bid", consumes = "application/json")
  public ResponseEntity<byte[]> bidJson(@RequestBody byte[] body) {
    return answer(body, false);
  }

  private ResponseEntity<byte[]> answer(byte[] body, boolean protobuf) {
    long startNanos = System.nanoTime();

    BidRequest request;
    try {
      request = protobuf ? BidRequest.parseFrom(body) : parseJson(body);
    } catch (InvalidProtocolBufferException e) {
      metrics.bidOutcome("invalid");
      return ResponseEntity.badRequest().build();
    }

    long budgetNanos =
        DeadlineBudget.budgetMillis(request.getTmax(), props.deadline()) * NANOS_PER_MILLI;
    Optional<BidResponse> decision = engine.decide(request, burlBase, nurlBase);
    long elapsedNanos = System.nanoTime() - startNanos;
    metrics.recordBidLatency(elapsedNanos);

    if (elapsedNanos > budgetNanos) {
      // A late answer is worse than no answer: the exchange has already moved on.
      metrics.bidOutcome("deadline");
      return ResponseEntity.noContent().build();
    }
    if (decision.isEmpty()) {
      metrics.bidOutcome("nobid");
      return ResponseEntity.noContent().build();
    }
    metrics.bidOutcome("bid");
    return protobuf ? protobufOk(decision.get()) : jsonOk(decision.get());
  }

  private static ResponseEntity<byte[]> protobufOk(BidResponse response) {
    return ResponseEntity.ok().contentType(PROTOBUF).body(response.toByteArray());
  }

  private static ResponseEntity<byte[]> jsonOk(BidResponse response) {
    try {
      String json = JsonFormat.printer().omittingInsignificantWhitespace().print(response);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.getBytes(StandardCharsets.UTF_8));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("cannot encode BidResponse as JSON", e);
    }
  }

  private static BidRequest parseJson(byte[] body) throws InvalidProtocolBufferException {
    BidRequest.Builder builder = BidRequest.newBuilder();
    JsonFormat.parser()
        .ignoringUnknownFields()
        .merge(new String(body, StandardCharsets.UTF_8), builder);
    return builder.build();
  }
}
