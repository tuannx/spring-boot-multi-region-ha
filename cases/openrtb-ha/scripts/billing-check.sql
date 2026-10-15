-- Billing reconciliation for one run. Psql variable: :start_ms (epoch milliseconds, run start).
--
-- 1) Ledger rows written since the run started. This must equal the loadgen report's
--    billing.distinctBilledBids: each billed bid id produces exactly one row, however many
--    billing notices it received.
SELECT 'ledger_rows_in_run=' || count(*)
FROM billing_events
WHERE created_at >= to_timestamp(:start_ms / 1000.0);

-- 2) Bid ids that appear more than once. The primary key makes this 0 by construction. It is
--    checked anyway, so a schema change that drops the key shows up here.
SELECT 'duplicate_bid_ids=' || count(*)
FROM (SELECT bid_id FROM billing_events GROUP BY bid_id HAVING count(*) > 1) d;

-- 3) Spend per campaign over all runs, in micro-USD.
SELECT 'spend campaign=' || campaign_id
       || ' billed=' || count(*)
       || ' spend_micros=' || sum(spend_micros)
FROM billing_events
GROUP BY campaign_id
ORDER BY campaign_id;
