-- Mock Aurora PostgreSQL functions for Region 1 (us-east-1)
-- Giúp AWS JDBC Driver detect topology như Aurora thật

-- Giả lập: trả về instance ID hiện tại
CREATE OR REPLACE FUNCTION pg_catalog.aurora_db_instance_identifier()
RETURNS TEXT AS $$
  SELECT 'postgres-us'::TEXT
$$ LANGUAGE SQL IMMUTABLE;

-- Local promotion state used by the Docker failover control-plane mock.
-- Real Aurora promotion is performed through AWS APIs, not this table.
CREATE TABLE IF NOT EXISTS public.failover_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  writer_mode BOOLEAN NOT NULL
);

INSERT INTO public.failover_control (singleton, writer_mode)
VALUES (TRUE, TRUE)
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
      ('postgres-us'::TEXT, 'MASTER_SESSION_ID'::TEXT, 10::INTEGER, 0::INTEGER, NOW()::TIMESTAMP),
      ('postgres-eu'::TEXT, 'postgres-eu'::TEXT,          8::INTEGER, 85::INTEGER, NOW()::TIMESTAMP);
  ELSE
    RETURN QUERY VALUES
      ('postgres-us'::TEXT, 'postgres-us'::TEXT,          10::INTEGER, 85::INTEGER, NOW()::TIMESTAMP),
      ('postgres-eu'::TEXT, 'MASTER_SESSION_ID'::TEXT,     8::INTEGER, 0::INTEGER, NOW()::TIMESTAMP);
  END IF;
END;
$$ LANGUAGE plpgsql STABLE;

-- Giả lập: check instance hiện tại có phải writer không
-- (chỉ instance-us-001 là writer)
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
  region VARCHAR(50) DEFAULT 'us-east-1',
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
  ('Global Product A', 29.99, 'us-east-1'),
  ('Global Product B', 49.99, 'us-east-1'),
  ('Regional Product US', 19.99, 'us-east-1');

-- The local Docker databases are independent, so enforce the single-writer
-- invariant at the table boundary instead of treating writer_mode as a
-- topology-only flag. The acceptance test demotes this database before EU is
-- promoted and proves that product writes are rejected here afterwards.
CREATE OR REPLACE FUNCTION public.require_product_writer_mode()
RETURNS TRIGGER AS $$
BEGIN
  IF NOT pg_catalog.aurora_is_writer() THEN
    RAISE EXCEPTION 'product writes are fenced on reader postgres-us'
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
