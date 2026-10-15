package com.example.openrtb.loadgen;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.iabtechlab.openrtb.v2.OpenRtb.BidRequest;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Pre-encodes a pool of distinct bid requests in both formats. Encoding happens once, before the
 * timed run, so the generator spends its CPU budget on sending, not on building messages.
 */
final class RequestFactory {

  private static final double[] FLOORS = {0.5, 1.0, 2.0, 5.0};
  private static final String UA =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

  private final byte[][] protoBodies;
  private final byte[][] jsonBodies;

  RequestFactory(int tmaxMs, long seed, int variants) {
    Random random = new Random(seed);
    protoBodies = new byte[variants][];
    jsonBodies = new byte[variants][];
    for (int i = 0; i < variants; i++) {
      BidRequest request = newRequest(random, i, tmaxMs);
      protoBodies[i] = request.toByteArray();
      jsonBodies[i] = toJson(request).getBytes(StandardCharsets.UTF_8);
    }
  }

  byte[] body(long index, Options.Format format) {
    int slot = (int) (index % protoBodies.length);
    return format == Options.Format.PROTO ? protoBodies[slot] : jsonBodies[slot];
  }

  /** Mean encoded size of a request body in this format, for the payload comparison. */
  double averageBodyBytes(Options.Format format) {
    byte[][] bodies = format == Options.Format.PROTO ? protoBodies : jsonBodies;
    long sum = 0;
    for (byte[] body : bodies) {
      sum += body.length;
    }
    return (double) sum / bodies.length;
  }

  private static BidRequest newRequest(Random random, int i, int tmaxMs) {
    BidRequest.Builder builder =
        BidRequest.newBuilder()
            .setId("lg-" + i + "-" + Long.toHexString(random.nextLong()))
            .setTmax(tmaxMs)
            .addCur("USD")
            .setSite(
                BidRequest.Site.newBuilder()
                    .setDomain("publisher.example")
                    .setPage("https://publisher.example/articles/" + random.nextInt(10_000)))
            .setDevice(
                BidRequest.Device.newBuilder()
                    .setUa(UA)
                    .setIp("203.0.113." + random.nextInt(256)))
            .setUser(BidRequest.User.newBuilder().setId(Long.toHexString(random.nextLong())));
    int imps = 1 + random.nextInt(2);
    for (int n = 1; n <= imps; n++) {
      builder.addImp(
          BidRequest.Imp.newBuilder()
              .setId(Integer.toString(n))
              .setBidfloor(FLOORS[random.nextInt(FLOORS.length)])
              .setBanner(BidRequest.Banner.newBuilder().setW(300).setH(250)));
    }
    return builder.build();
  }

  private static String toJson(BidRequest request) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(request);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("cannot encode request as JSON", e);
    }
  }
}
