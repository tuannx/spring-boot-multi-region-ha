-- Idempotent: runs on every startup. Safe to run from several instances at once.

-- Billing ledger. bid_id is the primary key. The billing insert uses ON CONFLICT (bid_id)
-- DO NOTHING, which makes repeated billing notices a no-op.
CREATE TABLE IF NOT EXISTS billing_events (
  bid_id       VARCHAR(64)  PRIMARY KEY,
  imp_id       TEXT         NOT NULL,
  campaign_id  VARCHAR(64)  NOT NULL,
  spend_micros BIGINT       NOT NULL CHECK (spend_micros >= 0),
  instance_id  VARCHAR(64)  NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS billing_events_campaign_idx ON billing_events (campaign_id);

-- Running spend per campaign, updated in the same statement as each billing insert. The bidder
-- reads this table (one row per campaign) instead of summing the whole ledger every refresh.
CREATE TABLE IF NOT EXISTS campaign_totals (
  campaign_id  VARCHAR(64)  PRIMARY KEY,
  spent_micros BIGINT       NOT NULL CHECK (spent_micros >= 0)
);

-- One-time backfill for ledgers written before campaign_totals existed. It runs only while the
-- totals table is empty, so ordinary restarts do not rescan the ledger. Without this, an upgraded
-- bidder would see every campaign as unspent.
INSERT INTO campaign_totals (campaign_id, spent_micros)
SELECT campaign_id, SUM(spend_micros)
FROM billing_events
WHERE NOT EXISTS (SELECT 1 FROM campaign_totals)
GROUP BY campaign_id
ON CONFLICT (campaign_id) DO NOTHING;
