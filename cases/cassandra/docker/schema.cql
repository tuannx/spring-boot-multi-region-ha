CREATE KEYSPACE IF NOT EXISTS multiregion
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'us-east-1': 3,
  'eu-west-1': 3
}
AND durable_writes = true;

CREATE TABLE IF NOT EXISTS multiregion.catalog_items (
  id uuid PRIMARY KEY,
  name text,
  price decimal,
  origin_region text,
  created_at timestamp,
  updated_at timestamp
);
