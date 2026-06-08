#!/usr/bin/env bash
# ============================================
# Multi-Region Failover Test Script
# ============================================
# Simulates Aurora multi-region failover:
#   1. Check initial health of both regions
#   2. Kill primary (us-east-1) database
#   3. Verify failover detection
#   4. Activate secondary (eu-west-1) as new primary
#   5. Verify recovery
#   6. Bring primary back online
# ============================================

set -euo pipefail

ROUTER_URL="${ROUTER_URL:-http://localhost:8000}"
US_URL="${US_URL:-http://localhost:8080}"
EU_URL="${EU_URL:-http://localhost:8081}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# ============================================
# Step 1: Check initial health
# ============================================
info "Step 1: Checking initial health of both regions..."

echo "--- Health: US Region ---"
curl -sf "${US_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
echo "--- Health: EU Region ---"
curl -sf "${EU_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
echo "--- Topology ---"
curl -sf "${US_URL}/admin/topology" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
info "Step 2: Creating a test product via primary..."
curl -sf -X POST "${US_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Failover Test Product","price":99.99}' 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (failed)"

echo ""
# ============================================
# Step 3: Kill primary database
# ============================================
info "Step 3: Killing primary (us-east-1) database to simulate outage..."
docker stop multiregion-us 2>/dev/null || true
echo "  Stopped container: multiregion-us"
sleep 5

echo ""
info "Step 4: Check health after primary outage..."
echo "--- US Region Health (should be DEGRADED) ---"
curl -sf "${US_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable - expected)"

echo ""
echo "--- EU Region Health (should still be UP) ---"
curl -sf "${EU_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
# ============================================
# Step 4: Activate failover on EU
# ============================================
info "Step 5: Activating failover on EU (secondary) region..."
curl -sf -X POST "${EU_URL}/admin/failover-activate" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (failed)"

echo ""
info "Step 6: Checking EU region is now handling requests..."
echo "--- EU Health (should show activated) ---"
curl -sf "${EU_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
# ============================================
# Step 5: Recover primary
# ============================================
info "Step 7: Restarting primary (us-east-1) database..."
docker start multiregion-us 2>/dev/null || true
echo "  Started container: multiregion-us"
sleep 10

echo ""
info "Step 8: Final health check..."
echo "--- Final US Health ---"
curl -sf "${US_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"
echo ""
echo "--- Final EU Health ---"
curl -sf "${EU_URL}/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"
echo ""
echo "--- Final Topology ---"
curl -sf "${US_URL}/admin/topology" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (unreachable)"

echo ""
info "=== Failover Test Complete ==="
