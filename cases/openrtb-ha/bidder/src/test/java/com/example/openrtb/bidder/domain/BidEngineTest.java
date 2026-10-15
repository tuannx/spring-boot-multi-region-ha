package com.example.openrtb.bidder.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.openrtb.bidder.budget.BudgetCache;
import com.example.openrtb.bidder.config.BidderProperties.Campaign;
import com.iabtechlab.openrtb.v2.OpenRtb.BidRequest;
import com.iabtechlab.openrtb.v2.OpenRtb.BidResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BidEngineTest {

  private static final long ONE_THOUSAND_USD_MICROS = 1_000_000_000L;
  private static final String BURL = "https://bidder.test/burl";
  private static final String NURL = "https://bidder.test/nurl";

  private static Campaign campaign(String id, double cpm) {
    return new Campaign(id, cpm, 1_000, "cr-" + id, "<a></a>", List.of("adv.example"));
  }

  private static BidRequest bannerRequest(String impId, double bidfloor) {
    return BidRequest.newBuilder()
        .setId("req-1")
        .addImp(
            BidRequest.Imp.newBuilder()
                .setId(impId)
                .setBidfloor(bidfloor)
                .setBanner(BidRequest.Banner.newBuilder().setW(300).setH(250)))
        .build();
  }

  @Test
  void picksHighestCpmCampaignThatClearsFloor() {
    BudgetCache budget =
        new BudgetCache(Map.of("low", ONE_THOUSAND_USD_MICROS, "high", ONE_THOUSAND_USD_MICROS));
    BidEngine engine =
        new BidEngine(List.of(campaign("low", 2.0), campaign("high", 4.5)), budget, 300);

    Optional<BidResponse> out = engine.decide(bannerRequest("1", 1.0), BURL, NURL);

    assertThat(out).isPresent();
    BidResponse.Bid bid = out.get().getSeatbid(0).getBid(0);
    assertThat(bid.getCid()).isEqualTo("high");
    assertThat(bid.getPrice()).isEqualTo(4.5);
    assertThat(bid.getExp()).isEqualTo(300);
    assertThat(bid.getBurl())
        .startsWith(BURL + "?bid=")
        .contains("&imp=1&cid=high&price=${AUCTION_PRICE}");
    assertThat(bid.getNurl()).startsWith(NURL + "?bid=");
  }

  @Test
  void noBidWhenNoCampaignClearsFloor() {
    BudgetCache budget = new BudgetCache(Map.of("a", ONE_THOUSAND_USD_MICROS));
    BidEngine engine = new BidEngine(List.of(campaign("a", 2.0)), budget, 300);

    assertThat(engine.decide(bannerRequest("1", 5.0), BURL, NURL)).isEmpty();
  }

  @Test
  void skipsCampaignWhoseBudgetCannotCoverOneImpression() {
    // One impression at 4.50 CPM costs 4_500 micro-USD; "high" has only 4_000 left.
    BudgetCache budget = new BudgetCache(Map.of("low", ONE_THOUSAND_USD_MICROS, "high", 4_000L));
    BidEngine engine =
        new BidEngine(List.of(campaign("low", 2.0), campaign("high", 4.5)), budget, 300);

    Optional<BidResponse> out = engine.decide(bannerRequest("1", 1.0), BURL, NURL);

    assertThat(out).isPresent();
    assertThat(out.get().getSeatbid(0).getBid(0).getCid()).isEqualTo("low");
  }

  @Test
  void ignoresImpressionsWithoutBanner() {
    BudgetCache budget = new BudgetCache(Map.of("a", ONE_THOUSAND_USD_MICROS));
    BidEngine engine = new BidEngine(List.of(campaign("a", 4.5)), budget, 300);
    BidRequest videoOnly =
        BidRequest.newBuilder()
            .setId("req-2")
            .addImp(BidRequest.Imp.newBuilder().setId("1").setBidfloor(0.0))
            .build();

    assertThat(engine.decide(videoOnly, BURL, NURL)).isEmpty();
  }

  @Test
  void urlEncodesExchangeSuppliedImpressionId() {
    BudgetCache budget = new BudgetCache(Map.of("a", ONE_THOUSAND_USD_MICROS));
    BidEngine engine = new BidEngine(List.of(campaign("a", 4.5)), budget, 300);

    Optional<BidResponse> out = engine.decide(bannerRequest("a&b c", 0.0), BURL, NURL);

    assertThat(out).isPresent();
    assertThat(out.get().getSeatbid(0).getBid(0).getBurl()).contains("&imp=a%26b+c&cid=a&");
  }
}
