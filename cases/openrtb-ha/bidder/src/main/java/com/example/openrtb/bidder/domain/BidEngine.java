package com.example.openrtb.bidder.domain;

import com.example.openrtb.bidder.budget.BudgetCache;
import com.example.openrtb.bidder.config.BidderProperties.Campaign;
import com.iabtechlab.openrtb.v2.OpenRtb.BidRequest;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse.Bid;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse.SeatBid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal banner bid decision for the POC.
 *
 * <p>For each banner impression it picks the highest-CPM campaign that clears the impression's
 * bidfloor and still has budget for one impression. Targeting, frequency capping and pacing are out
 * of scope here; the budget check is the only state the decision depends on.
 */
public final class BidEngine {

  private static final String SEAT = "poc-seat";

  private final List<Campaign> campaignsByCpmDesc;
  private final BudgetCache budget;
  private final int bidExpSeconds;

  public BidEngine(List<Campaign> campaigns, BudgetCache budget, int bidExpSeconds) {
    this.campaignsByCpmDesc =
        campaigns.stream().sorted((a, b) -> Double.compare(b.cpm(), a.cpm())).toList();
    this.budget = budget;
    this.bidExpSeconds = bidExpSeconds;
  }

  /** Spend for one impression at this CPM, in micro-USD (cpm / 1000 USD = cpm * 1000 micro-USD). */
  public static long spendMicrosPerImpression(double cpm) {
    return Math.round(cpm * 1_000d);
  }

  /**
   * @param burlBase public base URL for billing notices, e.g. https://bidder.example/burl
   * @param nurlBase public base URL for win notices, e.g. https://bidder.example/nurl
   * @return a bid response, or empty when the bidder should no-bid
   */
  public Optional<BidResponse> decide(BidRequest request, String burlBase, String nurlBase) {
    SeatBid.Builder seat = SeatBid.newBuilder().setSeat(SEAT);
    for (BidRequest.Imp imp : request.getImpList()) {
      if (!imp.hasBanner()) {
        continue;
      }
      Optional<Campaign> chosen = pickCampaign(imp.getBidfloor());
      if (chosen.isEmpty()) {
        continue;
      }
      seat.addBid(buildBid(imp.getId(), chosen.get(), burlBase, nurlBase));
    }
    if (seat.getBidCount() == 0) {
      return Optional.empty();
    }
    return Optional.of(
        BidResponse.newBuilder()
            .setId(request.getId())
            .setBidid(UUID.randomUUID().toString())
            .setCur("USD")
            .addSeatbid(seat)
            .build());
  }

  private Optional<Campaign> pickCampaign(double bidfloor) {
    for (Campaign c : campaignsByCpmDesc) {
      if (c.cpm() >= bidfloor && budget.remaining(c.id()) >= spendMicrosPerImpression(c.cpm())) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  private Bid.Builder buildBid(String impId, Campaign c, String burlBase, String nurlBase) {
    String bidId = UUID.randomUUID().toString();
    // impId comes from the exchange and may contain reserved characters, so it is encoded.
    String query =
        "?bid=" + bidId + "&imp=" + encode(impId) + "&cid=" + encode(c.id());
    return Bid.newBuilder()
        .setId(bidId)
        .setImpid(impId)
        .setPrice(c.cpm())
        .setAdm(c.adm())
        .addAllAdomain(c.adomain())
        .setCid(c.id())
        .setCrid(c.crid())
        .setExp(bidExpSeconds)
        .setNurl(nurlBase + query)
        // ${AUCTION_PRICE} is replaced by the exchange with the clearing CPM before the notice fires.
        .setBurl(burlBase + query + "&price=${AUCTION_PRICE}");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
