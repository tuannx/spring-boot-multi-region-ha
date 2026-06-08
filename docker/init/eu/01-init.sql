-- Mock Aurora PostgreSQL functions for Region 2 (eu-west-1, reader)
-- Giúp AWS JDBC Driver detect topology như Aurora thật

-- Giả lập: trả về instance ID hiện tại (reader instance)
CREATE OR REPLACE FUNCTION pg_catalog.aurora_db_instance_identifier()
RETURNS TEXT AS $$
  SELECT 'instance-eu-001'::TEXT
$$ LANGUAGE SQL IMMUTABLE;

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
BEGIN
  RETURN QUERY VALUES
    ('instance-us-001'::TEXT, 'MASTER_SESSION_ID'::TEXT, 10::INTEGER, 0::INTEGER, NOW()::TIMESTAMP),
    ('instance-us-002'::TEXT, 'instance-us-002'::TEXT,    5::INTEGER, 8::INTEGER,  NOW()::TIMESTAMP),
    ('instance-eu-001'::TEXT, 'instance-eu-001'::TEXT,    8::INTEGER, 85::INTEGER, NOW()::TIMESTAMP);
END;
$$ LANGUAGE plpgsql STABLE;

-- Giả lập: instance eu-west-1 không phải writer
CREATE OR REPLACE FUNCTION pg_catalog.aurora_is_writer()
RETURNS BOOLEAN AS $$
BEGIN
  RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE;

-- Tạo bảng demo (giống Region 1)
CREATE TABLE IF NOT EXISTS products (
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  region VARCHAR(50) DEFAULT 'eu-west-1',
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

-- Insert sample data with eu-west-1 region
INSERT INTO products (name, price, region) VALUES
  ('Global Product A', 29.99, 'eu-west-1'),
  ('Global Product B', 49.99, 'eu-west-1'),
  ('Regional Product EU', 39.99, 'eu-west-1');
