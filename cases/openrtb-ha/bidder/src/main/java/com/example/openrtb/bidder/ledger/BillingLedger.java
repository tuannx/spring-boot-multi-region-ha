package com.example.openrtb.bidder.ledger;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable billing ledger backed by PostgreSQL (Aurora PostgreSQL-compatible in production).
 *
 * <p>Idempotency: the primary key is the bid id. A billing notice (burl) can arrive more than once
 * because exchanges retry on timeouts and failovers replay in-flight requests. The
 * {@code ON CONFLICT DO NOTHING} insert makes the second and later deliveries a no-op.
 *
 * <p>Totals: each insert also upserts {@code campaign_totals} in the same statement, so the
 * per-campaign spend is always consistent with the ledger and the bidder reads one row per
 * campaign. A duplicate notice inserts nothing into the ledger, so it adds nothing to the totals.
 */
@Repository
public class BillingLedger {

  private final JdbcClient jdbc;

  public BillingLedger(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** @return true if this call created the row (first delivery), false if it was a duplicate. */
  public boolean recordBilling(
      String bidId, String impId, String campaignId, long spendMicros, String instanceId) {
    int rows =
        jdbc.sql(
                """
                WITH inserted AS (
                  INSERT INTO billing_events (bid_id, imp_id, campaign_id, spend_micros, instance_id)
                  VALUES (:bidId, :impId, :campaignId, :spendMicros, :instanceId)
                  ON CONFLICT (bid_id) DO NOTHING
                  RETURNING campaign_id, spend_micros
                )
                INSERT INTO campaign_totals (campaign_id, spent_micros)
                SELECT campaign_id, spend_micros FROM inserted
                ON CONFLICT (campaign_id)
                DO UPDATE SET spent_micros = campaign_totals.spent_micros + EXCLUDED.spent_micros
                """)
            .param("bidId", bidId)
            .param("impId", impId)
            .param("campaignId", campaignId)
            .param("spendMicros", spendMicros)
            .param("instanceId", instanceId)
            .update();
    // 1 when the ledger row and the totals update both happened; 0 for a duplicate.
    return rows == 1;
  }

  /** Total recorded spend per campaign, in micro-USD. Reads one row per campaign. */
  public Map<String, Long> spentByCampaign() {
    Map<String, Long> spent = new HashMap<>();
    jdbc.sql("SELECT campaign_id, spent_micros FROM campaign_totals")
        .query(
            (rs, rowNum) -> {
              spent.put(rs.getString("campaign_id"), rs.getLong("spent_micros"));
              return null;
            })
        .list();
    return spent;
  }
}
