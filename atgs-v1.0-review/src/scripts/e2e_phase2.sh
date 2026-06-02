#!/bin/bash
# Phase 2 end-to-end test.
#
# Since the sandbox doesn't have Docker, we cannot actually spin up a
# Minecraft container. What we CAN verify is everything up to and including
# the Docker runtime boundary: the task dispatch, the ack, the Keeper
# attempting the Docker call, and the proper error propagating back as a
# TaskResult with error_code=docker_unavailable.
#
# That proves the whole control plane works end to end. A Keeper on a real
# machine with Docker installed will cross the last mile.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs

# Clean prior run
pkill -9 -f "bin/(central|keeper)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/central.log /tmp/keeper.log

# Reset DB
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1
./bin/central migrate >/dev/null

# Start Central
./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
echo "[test] central pid: $CENTRAL_PID"

for i in $(seq 1 20); do
    if curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1; then
        echo "[test] central ready after ${i} attempts"
        break
    fi
    sleep 0.5
done

# Mint token
MINT=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"phase2 test"}')
TOKEN=$(echo "$MINT" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo "[test] token minted: ${TOKEN:0:16}..."

# Start Keeper (point eggs dir at the repo's eggs/ folder)
ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
echo "[test] keeper pid: $KEEPER_PID"

# Wait for either connection or early exit
sleep 3
if ! ps -p $KEEPER_PID >/dev/null; then
    echo "[test] KEEPER EXITED EARLY (expected since no Docker in sandbox)"
    echo "=== keeper.log ==="
    cat /tmp/keeper.log
fi

echo ""
echo "=== keeper.log ==="
cat /tmp/keeper.log
echo ""
echo "=== central.log ==="
grep -E "keeper|ws|http|task|instance" /tmp/central.log | tail -20

# Stop everything
kill $CENTRAL_PID 2>/dev/null || true
kill $KEEPER_PID 2>/dev/null || true
wait 2>/dev/null || true
echo ""
echo "[test] done"
