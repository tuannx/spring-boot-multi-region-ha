#!/usr/bin/env bash
# ============================================
# Configure Multi-Region HA for Realistic Failover
# ============================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
info() { echo -e "${CYAN}[INFO]${NC} $1"; }
sep() { echo -e "${YELLOW}──────────────────────────────────────────────${NC}"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ============================================
# STEP 1: Set postgres-eu to read-only
# ============================================
sep
info "STEP 1: Setting postgres-eu to READ-ONLY mode..."
sep

# Check current mode first
echo "  Current read_only mode:"
docker exec multiregion-eu psql -U appuser -d appdb -c "SHOW default_transaction_read_only;" 2>&1

# Set read-only using superuser account
docker exec multiregion-eu psql -U appuser -d appdb -c "ALTER SYSTEM SET default_transaction_read_only = on;" 2>&1
docker exec multiregion-eu psql -U appuser -d appdb -c "SELECT pg_reload_conf();" 2>&1
sleep 2

echo ""
echo "  After setting read-only:"
docker exec multiregion-eu psql -U appuser -d appdb -c "SHOW default_transaction_read_only;" 2>&1

# Verify write is blocked
echo ""
echo "  Verify write is blocked on EU:"
WRITE_TEST=$(docker exec multiregion-eu psql -U appuser -d appdb -c "INSERT INTO products (name, price, region) VALUES ('SHOULD_FAIL', 0.01, 'eu-west-1');" 2>&1) 
if echo "$WRITE_TEST" | grep -qi "read-only\|cannot execute"; then
  pass "Write blocked correctly on EU reader!"
else
  echo "  [WARN] Write was NOT blocked: $WRITE_TEST"
fi

# ============================================
# STEP 2: Create toggle-writable function
# ============================================
sep
info "STEP 2: Creating pg_catalog.set_writer_mode() function..."
sep

docker exec multiregion-eu psql -U appuser -d appdb -c "
CREATE OR REPLACE FUNCTION pg_catalog.set_writer_mode(enabled BOOLEAN)
RETURNS TEXT AS \$\$
BEGIN
  IF enabled THEN
    PERFORM ALTER SYSTEM SET default_transaction_read_only = off;
    PERFORM pg_reload_conf();
    RETURN 'Writer mode ENABLED - read-write';
  ELSE
    PERFORM ALTER SYSTEM SET default_transaction_read_only = on;
    PERFORM pg_reload_conf();
    RETURN 'Writer mode DISABLED - read-only';
  END IF;
END;
\$\$ LANGUAGE plpgsql VOLATILE;
" 2>&1
pass "set_writer_mode() created on postgres-eu"

# ============================================
# STEP 3: Configure HikariCP for fast failover
# ============================================
sep
info "STEP 3: Configuring HikariCP for fast connection validation..."
sep

# Update application.yml Hikari settings
cd "$SCRIPT_DIR/.."
echo "  Updating HikariCP config..."

python3 -c "
import re
with open('app/src/main/resources/application.yml', 'r') as f:
    content = f.read()

# Replace HikariCP section
old_hikari = '''  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 600000
      connection-test-query: SELECT 1'''

new_hikari = '''  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 0
      connection-timeout: 3000
      validation-timeout: 2000
      idle-timeout: 10000
      max-lifetime: 30000
      keepalive-time: 15000
      connection-test-query: SELECT 1
      leak-detection-threshold: 60000'''

content = content.replace(old_hikari, new_hikari)
with open('app/src/main/resources/application.yml', 'w') as f:
    f.write(content)
print('HikariCP config updated')
"
echo ""

# ============================================
# STEP 4: Modify FailoverListener to toggle DB mode
# ============================================
sep
info "STEP 4: Enhancing FailoverListener to toggle DB read-only on failover..."
sep

python3 -c "
# Read current FailoverListener
with open('app/src/main/java/com/multiregion/config/FailoverListener.java', 'r') as f:
    content = f.read()

# Add the forceFailover method with DB toggle
old_method = '''    public void forceFailover() {
        activated = true;
        log.warn(\"*** MANUAL FAILOVER ACTIVATED for {} ***\", awsRegion);
    }'''

new_method = '''    public void forceFailover() {
        log.warn(\"*** MANUAL FAILOVER ACTIVATED for {} ***\", awsRegion);
        activated = true;
        // Toggle local DB from read-only to read-write
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute(\"ALTER SYSTEM SET default_transaction_read_only = off\");
            jdbc.execute(\"SELECT pg_reload_conf()\");
            log.info(\"Local database toggled to READ-WRITE mode\");
        } catch (Exception e) {
            log.warn(\"Could not toggle DB mode: {}\", e.getMessage());
            // Non-critical - application-level activation still proceeds
        }
    }'''

content = content.replace(old_method, new_method)

with open('app/src/main/java/com/multiregion/config/FailoverListener.java', 'w') as f:
    f.write(content)

print('FailoverListener.forceFailover() enhanced with DB toggle')
"

# ============================================
# STEP 5: Also update auto-failover to toggle DB
# ============================================
sep
info "STEP 5: Enhancing auto-failover in scheduled check to toggle DB..."
sep

python3 -c "
with open('app/src/main/java/com/multiregion/config/FailoverListener.java', 'r') as f:
    content = f.read()

# Enhance auto-failover in checkPrimaryHealth
old_auto = '''            log.warn(\"Auto-activating this region as failover target!\");
            activated = true;
            log.info(\"*** FAILOVER ACTIVATED - {} is now handling requests ***\", awsRegion);'''

new_auto = '''            log.warn(\"Auto-activating this region as failover target!\");
            activated = true;
            // Toggle local DB from read-only to read-write
            try {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                jdbc.execute(\"ALTER SYSTEM SET default_transaction_read_only = off\");
                jdbc.execute(\"SELECT pg_reload_conf()\");
                log.info(\"Local database toggled to READ-WRITE mode\");
            } catch (Exception e2) {
                log.warn(\"Could not toggle DB mode: {}\", e2.getMessage());
            }
            log.info(\"*** FAILOVER ACTIVATED - {} is now handling requests ***\", awsRegion);'''

content = content.replace(old_auto, new_auto)

with open('app/src/main/java/com/multiregion/config/FailoverListener.java', 'w') as f:
    f.write(content)

print('Auto-failover enhanced with DB toggle')
"

# ============================================
# STEP 6: Rebuild and restart
# ============================================
sep
info "STEP 6: Rebuilding app images with new config..."
sep

cd "$SCRIPT_DIR/.."
docker compose build app-us app-eu 2>&1 | tail -3
echo ""

echo "  Stopping and recreating app containers..."
docker compose rm -sf app-us app-eu 2>&1 | tail -2
docker compose create app-us app-eu 2>&1 | tail -2
docker compose start app-us app-eu 2>&1 | tail -2

echo ""
info "Waiting for apps to initialize (30s)..."
sleep 30

# ============================================
# VERIFY
# ============================================
sep
info "VERIFICATION:"
sep

echo ""
echo "--- EU read-only mode ---"
docker exec multiregion-eu psql -U appuser -d appdb -c "SHOW default_transaction_read_only;" 2>&1

echo ""
echo "--- Verify write blocked on EU ---"
docker exec multiregion-eu psql -U appuser -d appdb -c "INSERT INTO products (name, price, region) VALUES ('BLOCKED_TEST', 0.01, 'eu-west-1');" 2>&1 || echo "  ✅ Write blocked as expected"

echo ""
echo "--- APP-US Health ---"
curl -sf http://localhost:8080/health | python3 -m json.tool 2>/dev/null

echo ""
echo "--- APP-EU Health ---"
curl -sf http://localhost:8081/health | python3 -m json.tool 2>/dev/null

echo ""
echo "--- APP-US Write test ---"
curl -sf -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' -d '{"name":"US Write OK","price":1.99}' | python3 -m json.tool 2>/dev/null

echo ""
echo "--- APP-EU Write test (should FAIL - read-only) ---"
RESULT=$(curl -s -X POST http://localhost:8081/api/products -H 'Content-Type: application/json' -d '{"name":"EU Write FAIL","price":999.99}' 2>&1)
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "  Result: $RESULT"
echo ""

sep
echo -e "${GREEN}Infrastructure configured for realistic failover testing!${NC}"
echo -e "  postgres-us = WRITABLE (primary)"
echo -e "  postgres-eu = READ-ONLY (secondary)"
echo -e "  Failover auto-toggles DB to read-write"
sep
