#!/usr/bin/env bash
# ============================================
# Seed Sample Data Script
# ============================================
# Populates the multi-region product catalog
# via the API endpoints.
# ============================================

set -euo pipefail

US_URL="${US_URL:-http://localhost:8080}"
EU_URL="${EU_URL:-http://localhost:8081}"

echo "=== Seeding data via US region (primary) ==="

curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Global Widget","price":19.99}' && echo " - Created: Global Widget"

curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Premium Service","price":149.99}' && echo " - Created: Premium Service"

curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Enterprise License","price":999.99}' && echo " - Created: Enterprise License"

curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Cloud Storage 1TB","price":59.99}' && echo " - Created: Cloud Storage 1TB"

curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"API Access Monthly","price":29.99}' && echo " - Created: API Access Monthly"

echo ""
echo "=== Verifying data via US region ==="
curl -sf "${US_URL}/api/products" | python3 -m json.tool

echo ""
echo "=== Verifying data via EU region (read replica) ==="
curl -sf "${EU_URL}/api/products" | python3 -m json.tool

echo ""
echo "=== Seed Complete ==="
