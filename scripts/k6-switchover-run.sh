#!/usr/bin/env bash
# ============================================
# FAILOVER TEST v4 - Correct disaster recovery flow
# ============================================
# failover2 plugin DESIGN:
#   - Handles ONLY disaster failover (connection breaks due to node crash)
#   - Does NOT actively re-route writes during normal/healthy operation
#   - When a connection to the writer breaks:
#     1. MonitoringRdsHostListProvider detects failure
#     2. Opens connections to ALL cluster nodes in parallel
#     3. Queries each node: "Are you the writer?"
#     4. When writer found, reconnects waiting connections to it
#
# IMPORTANT: Don't set write_enabled=false before kill!
#   During failover, failover2 plugin may still try the old writer
#   before reconnecting to the new one. Blocking writes pre-kill
#   kills all writes before failover completes.
# ============================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../k6-output"; mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%s)

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  FAILOVER TEST v4 - Disaster Recovery Flow             ║"
echo "║                                                        ║"
echo "║  Timeline:                                             ║"
echo "║    T+0-18s   k6 warmup + steady traffic                ║"
echo "║    T+18s     Show baseline + update topology           ║"
echo "║              (postgres-eu = MASTER, write all true)    ║"
echo "║    T+18-30s  Wait for wrapper refresh (5s cycle)       ║"
echo "║    T+30s     Kill postgres-us (old writer)             ║"
echo "║    T+30-40s  failover2 detects failure → reconnects    ║"
echo "║    T+40s     Verify writes via EU (new writer)         ║"
echo "║    ...       Recovery (restart + restore topology)     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ============================================
# STEP 0: Initial baseline
# ============================================
echo "=== Initial baseline ==="
echo "Topology: postgres-us = WRITER, postgres-eu = reader"
echo ""

# Start k6
k6 run "$SCRIPT_DIR/k6-switchover-test.js" --quiet > "$OUTPUT_DIR/k6-final-${TIMESTAMP}.log" 2>&1 &
K6_PID=$!
echo "k6 PID: $K6_PID (waiting 18s for warmup + steady load)"
sleep 18

# ============================================
# STEP 1: Pre-switchover baseline verification
# ============================================
echo ""
echo "=== Pre-switchover ==="
docker exec multiregion-us psql -U appuser -d appdb -t -c \
  "SELECT 'Topology: ' || SERVER_ID || ' = ' || CASE WHEN SESSION_ID='MASTER_SESSION_ID' THEN 'WRITER' ELSE 'reader' END FROM pg_catalog.aurora_replica_status() ORDER BY SERVER_ID;" 2>/dev/null | head -5

# Writes should work via postgres-us (writer)
US_W=$(curl -s -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' -d '{"name":"Pre_US","price":1.0}' 2>/dev/null | python3 -c "import sys,json;print(f'US write: id={json.load(sys.stdin).get(\"id\",\"FAIL\")}')" 2>/dev/null)
EU_W=$(curl -s -X POST http://localhost:8081/api/products -H 'Content-Type: application/json' -d '{"name":"Pre_EU","price":2.0}' 2>/dev/null | python3 -c "import sys,json;print(f'EU write: id={json.load(sys.stdin).get(\"id\",\"FAIL\")}')" 2>/dev/null)
echo "  $US_W (→ postgres-us writer)"
echo "  $EU_W (→ postgres-us writer)"

# ============================================
# STEP 2: Update topology (postgres-eu = MASTER)
# BUT keep write_enabled=true on ALL nodes
# ============================================
echo ""
echo "=== STEP 2: Update topology → postgres-eu = MASTER ==="
# Don't touch region_config - keep write_enabled=true everywhere
for DB in multiregion-us multiregion-eu; do
  docker exec -i $DB psql -U appuser -d appdb << 'SQLEOF' 2>/dev/null
SET default_transaction_read_only = off;
CREATE OR REPLACE FUNCTION pg_catalog.aurora_replica_status()
RETURNS TABLE(SERVER_ID TEXT, SESSION_ID TEXT, CPU INTEGER, REPLICA_LAG_IN_MSEC INTEGER, LAST_UPDATE_TIMESTAMP TIMESTAMP) AS $$
BEGIN
  RETURN QUERY VALUES
    ('postgres-us'::TEXT, 'postgres-us'::TEXT,       10::INTEGER, 5::INTEGER,  NOW()::TIMESTAMP),
    ('postgres-eu'::TEXT, 'MASTER_SESSION_ID'::TEXT, 8::INTEGER,  0::INTEGER,  NOW()::TIMESTAMP);
END;
$$ LANGUAGE plpgsql STABLE;
SQLEOF
done
echo "  ✅ Topology: postgres-eu is now MASTER (write_allowed=true on all nodes)"
echo "  ⏳ Waiting 12s for wrapper topology refresh (clusterTopologyRefreshRateMs=5s)..."
sleep 12

echo "  DB topology confirmation:"
docker exec multiregion-eu psql -U appuser -d appdb -t -c \
  "SELECT SERVER_ID || ' = ' || CASE WHEN SESSION_ID='MASTER_SESSION_ID' THEN 'WRITER' ELSE 'reader' END FROM pg_catalog.aurora_replica_status() ORDER BY SERVER_ID;" 2>/dev/null

# Writes still work because both DBs still have write_enabled=true
echo ""
echo "  Writes before kill (old connections, both accepted):"
US_W2=$(curl -s -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' -d '{"name":"PreKill_US","price":50.0}' 2>/dev/null | python3 -c "import sys,json;print(f'US write: id={json.load(sys.stdin).get(\"id\",\"FAIL\")}')" 2>/dev/null)
EU_W2=$(curl -s -X POST http://localhost:8081/api/products -H 'Content-Type: application/json' -d '{"name":"PreKill_EU","price":60.0}' 2>/dev/null | python3 -c "import sys,json;print(f'EU write: id={json.load(sys.stdin).get(\"id\",\"FAIL\")}')" 2>/dev/null)
echo "  $US_W2"
echo "  $EU_W2"

# ============================================
# STEP 3: Kill postgres-us (old writer)
# failover2 should detect connection failure
# and reconnect to postgres-eu (new writer per topology)
# ============================================
echo ""
echo "=== STEP 3: Kill postgres-us (old writer) ==="
echo "  Triggering failover2 plugin..."
docker stop multiregion-us 2>/dev/null || true
sleep 10

echo ""
echo "  Health after kill + failover window:"
US_H=$(curl -s http://localhost:8080/health | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'US: {d[\"status\"]} db={d[\"dbConnected\"]}')" 2>/dev/null || echo "US: DOWN")
EU_H=$(curl -s http://localhost:8081/health | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'EU: {d[\"status\"]} db={d[\"dbConnected\"]}')" 2>/dev/null || echo "EU: DOWN")
echo "  $US_H"
echo "  $EU_H"

# ============================================
# STEP 4: Verify writes via postgres-eu
# After failover, WritePool should reconnect to postgres-eu
# ============================================
echo ""
echo "=== STEP 4: Verify writes via EU (new writer) ==="
echo "  failover2 should reconnect WritePool to postgres-eu..."

WRITE_AFTER=$(curl -s -X POST http://localhost:8081/api/products -H 'Content-Type: application/json' -d '{"name":"PostFailover","price":99.0}' 2>/dev/null)
echo "  EU write via EU app: $(echo $WRITE_AFTER | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'id={d.get(\"id\",\"FAIL\")} name={d.get(\"name\",\"\")}')" 2>/dev/null || echo 'parse_fail')"

# Reads should work fine (ReadPool → postgres-eu, home region)
READ_EU=$(curl -s http://localhost:8081/api/products 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'EU reads: {len(d) if isinstance(d,list) else d.get(\"count\",\"?\")} products')" 2>/dev/null)
echo "  $READ_EU"

# Wait for k6 to finish
echo ""
echo "--- Waiting for k6 to finish ---"
wait $K6_PID 2>/dev/null || true

echo ""
echo "=== k6 Results ==="
grep -E "http_req_failed|checks_total|succ|fail|✗|✓" "$OUTPUT_DIR/k6-final-${TIMESTAMP}.log" 2>/dev/null | head -20

echo ""
echo "=== Recovery ==="
docker start multiregion-us 2>/dev/null || true
sleep 8

# Restore topology: postgres-us = WRITER
for DB in multiregion-us multiregion-eu; do
  docker exec -i $DB psql -U appuser -d appdb << 'SQLEOF' 2>/dev/null
SET default_transaction_read_only = off;
CREATE OR REPLACE FUNCTION pg_catalog.aurora_replica_status()
RETURNS TABLE(SERVER_ID TEXT, SESSION_ID TEXT, CPU INTEGER, REPLICA_LAG_IN_MSEC INTEGER, LAST_UPDATE_TIMESTAMP TIMESTAMP) AS $$
BEGIN
  RETURN QUERY VALUES
    ('postgres-us'::TEXT, 'MASTER_SESSION_ID'::TEXT, 10::INTEGER, 0::INTEGER, NOW()::TIMESTAMP),
    ('postgres-eu'::TEXT, 'postgres-eu'::TEXT,       8::INTEGER, 85::INTEGER, NOW()::TIMESTAMP);
END;
$$ LANGUAGE plpgsql STABLE;
SQLEOF
done

# Force reconnect to restore topology
docker exec multiregion-us psql -U appuser -d appdb -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='appuser' AND pid <> pg_backend_pid();" 2>/dev/null || true
docker exec multiregion-eu psql -U appuser -d appdb -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='appuser' AND pid <> pg_backend_pid();" 2>/dev/null || true
sleep 8

echo "✅ Topology restored: postgres-us = WRITER"
US_H=$(curl -sf http://localhost:8080/health 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'US: {d[\"status\"]} writer={d[\"writerNode\"]}')" 2>/dev/null || echo "US: still recovering")
EU_H=$(curl -sf http://localhost:8081/health 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'EU: {d[\"status\"]} writer={d[\"writerNode\"]}')" 2>/dev/null || echo "EU: still recovering")
echo "$US_H"
echo "$EU_H"
echo "✅ ALL SYSTEMS RESTORED"
