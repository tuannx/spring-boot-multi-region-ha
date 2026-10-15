package com.example.openrtb.bidder.api;

import com.example.openrtb.bidder.ledger.BillingService;
import com.example.openrtb.bidder.metrics.BidMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notice endpoints called by the exchange.
 *
 * <ul>
 *   <li>{@code /nurl} (win notice) is informational and not billable.
 *   <li>{@code /burl} (billing notice) is billable. It is idempotent on {@code bid}: exchanges retry,
 *       and a repeated notice returns 200 without a second charge. A ledger failure returns 503 so
 *       the exchange retries later.
 * </ul>
 */
@RestController
public class NoticeController {

  private static final int MAX_BID_ID_LENGTH = 64;
  private static final int MAX_IMP_ID_LENGTH = 256;

  private final BillingService billing;
  private final BidMetrics metrics;

  public NoticeController(BillingService billing, BidMetrics metrics) {
    this.billing = billing;
    this.metrics = metrics;
  }

  @GetMapping("/burl")
  public ResponseEntity<Void> billing(
      @RequestParam("bid") String bid,
      @RequestParam("imp") String imp,
      @RequestParam("cid") String cid,
      @RequestParam(value = "price", required = false) Double price) {
    if (bid.isEmpty()
        || bid.length() > MAX_BID_ID_LENGTH
        || imp.length() > MAX_IMP_ID_LENGTH) {
      return ResponseEntity.badRequest().build();
    }
    try {
      return switch (billing.settle(bid, imp, cid, price)) {
        case RECORDED, DUPLICATE -> ResponseEntity.ok().build();
        case UNKNOWN_CAMPAIGN -> ResponseEntity.notFound().build();
      };
    } catch (DataAccessException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .header(HttpHeaders.RETRY_AFTER, "1")
          .build();
    }
  }

  @GetMapping("/nurl")
  public ResponseEntity<Void> win(@RequestParam("bid") String bid) {
    metrics.winNotice();
    return ResponseEntity.ok().build();
  }
}
