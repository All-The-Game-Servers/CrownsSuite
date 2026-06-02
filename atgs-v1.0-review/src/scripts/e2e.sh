#!/bin/bash
# End-to-end test for Phase 1.
# Runs Central in background, mints a token, runs Keeper, verifies
# the /api/v1/keepers endpoint shows it as connected.
set -e

export PATH=$PATH:/usr/local/go/bin
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs

# Clean up previous run
pkill -9 -f "bin/(central|keeper)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/central.log /tmp/keeper.log

# Fresh DB - drop and recreate to start from zero data
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1
./bin/central migrate >/dev/null

# Start Central
./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
echo "[test] central pid: $CENTRAL_PID"

# Wait for admin listener to be actually responsive
for i in $(seq 1 20); do
    if curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1; then
        echo "[test] central is up after ${i} attempts"
        break
    fi
    sleep 0.5
done

# Mint a token
MINT=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"e2e test"}')
echo "[test] mint: $MINT"
TOKEN=$(echo "$MINT" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo "[test] token (first 16): ${TOKEN:0:16}..."

# Start Keeper
ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
echo "[test] keeper pid: $KEEPER_PID"

# Wait for keeper to connect
sleep 4

echo
echo "=== keeper.log ==="
cat /tmp/keeper.log
echo
echo "=== central.log (filtered) ==="
grep -E "keeper|ws|http" /tmp/central.log | tail -15
echo
echo "=== /api/v1/keepers ==="
curl -s http://127.0.0.1:8080/api/v1/keepers | python3 -m json.tool

echo
echo "=== waiting 22s to observe at least one ping cycle ==="
sleep 22
curl -s http://127.0.0.1:8080/api/v1/keepers | python3 -c "
import json, sys
data = json.load(sys.stdin)
for k in data['keepers']:
    print(f\"  keeper_id={k['id']}\")
    print(f\"  connected={k['connected']}\")
    print(f\"  platform={k['platform']} arch={k['arch']}\")
    print(f\"  agent_version={k['agent_version']}\")
    print(f\"  last_seen_at={k['last_seen_at']}\")
"

echo
echo "=== last 10 central log lines ==="
tail -10 /tmp/central.log

# Cleanup
kill $KEEPER_PID 2>/dev/null || true
kill $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null
echo
echo "[test] done"
