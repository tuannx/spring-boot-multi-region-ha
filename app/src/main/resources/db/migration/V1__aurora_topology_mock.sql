-- V1: Aurora topology mock, demo tables, fencing trigger, and seed data.
--
-- Unifies the former docker/init/us + docker/init/eu 01-init.sql pair, which
-- differed only in region-varying values. Each application instance migrates
-- its own home database, so placeholders resolve per region:
--   flywaySelfInstance / flywayPeerInstance : postgres-us / postgres-eu
--   flywaySelfCpu / flywayPeerCpu           : 10 / 8 (per-instance mock load)
--   flywayRegion                             : us-east-1 / eu-west-1
--   flywayWriterMode                         : TRUE (us) / FALSE (eu)
--   flywayRegionalProductName/Price          : regional seed row
--
-- Replica row order is self-first in both branches. Consumers filter by
-- SESSION_ID or ORDER BY SERVER_ID, so the order is immaterial; the row SET
-- per branch matches the legacy scripts exactly. products.id keeps the legacy
-- SERIAL type verbatim so baseline-on-migrate stays honest for pre-existing
-- volumes; V2 converges it to the BIGINT the Product entity requires.
-- Seed INSERTs intentionally run BEFORE the fencing trigger is created, so the
-- initial rows land even on the reader region.

-- Mock Aurora PostgreSQL functions for this region.
-- Giúp AWS JDBC Driver detect topology như Aurora thật

-- Giả lập: trả về instance ID hiện tại
CREATE OR REPLACE FUNCTION pg_catalog.aurora_db_instance_identifier()
RETURNS TEXT AS $$
  SELECT '${flywaySelfInstance}'::TEXT
$$ LANGUAGE SQL IMMUTABLE;

-- Local promotion state used by the Docker failover control-plane mock.
-- Real Aurora promotion is performed through AWS APIs, not this table.
CREATE TABLE IF NOT EXISTS public.failover_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  writer_mode BOOLEAN NOT NULL
);

INSERT INTO public.failover_control (singleton, writer_mode)
VALUES (TRUE, ${flywayWriterMode})
ON CONFLICT (singleton) DO NOTHING;

-- Schema match với query của AWS JDBC Driver:
-- SELECT SERVER_ID, CASE WHEN SESSION_ID = 'MASTER_SESSION_ID' THEN TRUE ELSE FALSE END,
--   CPU, COALESCE(REPLICA_LAG_IN_MSEC, 0), LAST_UPDATE_TIMESTAMP
-- FROM pg_catalog.aurora_replica_status()
CREATE OR REPLACE FUNCTION pg_catalog.aurora_replica_status()
RETURNS TABLE(
  SERVER_ID TEXT,
  SESSION_ID TEXT,
  CPU INTEGER,
  REPLICA_LAG_IN_MSEC INTEGER,
  LAST_UPDATE_TIMESTAMP TIMESTAMP
) AS $$
DECLARE
  local_writer BOOLEAN;
BEGIN
  SELECT writer_mode INTO local_writer
  FROM public.failover_control
  WHERE singleton = TRUE;

  IF local_writer THEN
    RETURN QUERY VALUES
      ('${flywaySelfInstance}'::TEXT, 'MASTER_SESSION_ID'::TEXT, ${flywaySelfCpu}::INTEGER, 0::INTEGER, NOW()::TIMESTAMP),
      ('${flywayPeerInstance}'::TEXT, '${flywayPeerInstance}'::TEXT,          ${flywayPeerCpu}::INTEGER, 85::INTEGER, NOW()::TIMESTAMP);
  ELSE
    RETURN QUERY VALUES
      ('${flywaySelfInstance}'::TEXT, '${flywaySelfInstance}'::TEXT,          ${flywaySelfCpu}::INTEGER, 85::INTEGER, NOW()::TIMESTAMP),
      ('${flywayPeerInstance}'::TEXT, 'MASTER_SESSION_ID'::TEXT, ${flywayPeerCpu}::INTEGER, 0::INTEGER, NOW()::TIMESTAMP);
  END IF;
END;
$$ LANGUAGE plpgsql STABLE;

-- Giả lập: check instance hiện tại có phải writer không
CREATE OR REPLACE FUNCTION pg_catalog.aurora_is_writer()
RETURNS BOOLEAN AS $$
  SELECT writer_mode
  FROM public.failover_control
  WHERE singleton = TRUE
$$ LANGUAGE SQL STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.set_writer_mode(enabled BOOLEAN)
RETURNS TEXT AS $$
BEGIN
  UPDATE public.failover_control
  SET writer_mode = enabled
  WHERE singleton = TRUE;

  RETURN CASE
    WHEN enabled THEN 'Writer mode ENABLED'
    ELSE 'Writer mode DISABLED'
  END;
END;
$$ LANGUAGE plpgsql VOLATILE;

-- Tạo bảng demo
CREATE TABLE IF NOT EXISTS products (
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  region VARCHAR(50) DEFAULT '${flywayRegion}',
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS region_events (
  id SERIAL PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  region VARCHAR(50) NOT NULL,
  details JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS queue_region_status (
  queue_name VARCHAR(128) NOT NULL,
  region VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  reason VARCHAR(255),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  PRIMARY KEY (queue_name, region)
);

INSERT INTO queue_region_status (queue_name, region, status, reason) VALUES
  ('orders', 'us-east-1', 'UP', 'seeded by region init'),
  ('orders', 'eu-west-1', 'UP', 'seeded by region init')
ON CONFLICT (queue_name, region) DO NOTHING;

-- Insert sample data
INSERT INTO products (name, price, region) VALUES
  ('Global Product A', 29.99, '${flywayRegion}'),
  ('Global Product B', 49.99, '${flywayRegion}'),
  ('${flywayRegionalProductName}', ${flywayRegionalProductPrice}, '${flywayRegion}');

-- The local Docker databases are independent, so enforce the single-writer
-- invariant at the table boundary instead of treating writer_mode as a
-- topology-only flag. The acceptance test demotes this database before EU is
-- promoted and proves that product writes are rejected here afterwards.
CREATE OR REPLACE FUNCTION public.require_product_writer_mode()
RETURNS TRIGGER AS $$
BEGIN
  IF NOT pg_catalog.aurora_is_writer() THEN
    RAISE EXCEPTION 'product writes are fenced on reader ${flywaySelfInstance}'
      USING ERRCODE = '25006';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS products_require_writer_mode ON products;
CREATE TRIGGER products_require_writer_mode
BEFORE INSERT OR UPDATE OR DELETE ON products
FOR EACH ROW EXECUTE FUNCTION public.require_product_writer_mode();
