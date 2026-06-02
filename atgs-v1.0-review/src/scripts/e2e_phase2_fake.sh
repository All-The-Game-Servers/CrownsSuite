#!/bin/bash
# Phase 2 end-to-end with fake Docker runtime.
#
# Proves the full control-plane pipeline:
#   Central POST /instances -> task dispatched -> keeper acks -> keeper "creates"
#   container in fake runtime -> result flows back -> instance row shows
#   container_id and state=created.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs

pkill -9 -f "bin/(central|keeper)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/central.log /tmp/keeper.log

su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1
./bin/central migrate >/dev/null

./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
echo "[test] central pid: $CENTRAL_PID"
for i in $(seq 1 20); do
    curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1 && break
    sleep 0.5
done
echo "[test] central ready"

TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"phase2 fake"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo "[test] token minted"

# Run keeper with FAKE docker
ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
ATGS_KEEPER_FAKE_DOCKER=true \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
echo "[test] keeper pid: $KEEPER_PID (fake docker)"

# Wait for keeper to connect
sleep 3

# Get the keeper id
KEEPER_ID=$(curl -s http://127.0.0.1:8080/api/v1/keepers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['keepers'][0]['id'])")
echo "[test] keeper id: $KEEPER_ID"

# Create an instance via the admin API
echo ""
echo "=== POST /api/v1/keepers/\$KEEPER_ID/instances ==="
CREATE_RESP=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Lowlight SMP Test",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
echo "$CREATE_RESP" | python3 -m json.tool
INSTANCE_ID=$(echo "$CREATE_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")
TASK_ID=$(echo "$CREATE_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['task_id'])")
echo ""
echo "[test] instance_id: $INSTANCE_ID"
echo "[test] task_id: $TASK_ID"

# Wait for task to complete
sleep 2

echo ""
echo "=== GET /api/v1/tasks/$TASK_ID ==="
curl -s http://127.0.0.1:8080/api/v1/tasks/$TASK_ID | python3 -m json.tool

echo ""
echo "=== GET /api/v1/keepers/$KEEPER_ID/instances ==="
curl -s http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances | python3 -m json.tool

# Start the instance
echo ""
echo "=== POST /api/v1/instances/$INSTANCE_ID/start ==="
curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE_ID/start | python3 -m json.tool
sleep 2

echo ""
echo "=== instance state after start ==="
curl -s http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    | python3 -c "
import json,sys
d = json.load(sys.stdin)
for i in d['instances']:
    print(f\"  state={i['state']} container_id={i['container_id']}\")
"

# Tail logs
echo ""
echo "=== GET /api/v1/instances/$INSTANCE_ID/logs ==="
curl -s "http://127.0.0.1:8080/api/v1/instances/$INSTANCE_ID/logs" | python3 -m json.tool

# Stop and delete
echo ""
echo "=== POST /api/v1/instances/$INSTANCE_ID/stop ==="
curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE_ID/stop >/dev/null
sleep 2
curl -s http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    | python3 -c "
import json,sys
d = json.load(sys.stdin)
for i in d['instances']:
    print(f\"  after stop: state={i['state']}\")
"

echo ""
echo "=== DELETE /api/v1/instances/$INSTANCE_ID ==="
curl -s -X DELETE http://127.0.0.1:8080/api/v1/instances/$INSTANCE_ID >/dev/null
sleep 2
INST_COUNT=$(curl -s http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('instances') or []))")
echo "  instances still listed: $INST_COUNT  (should be 0 after delete)"

# Wrap up
echo ""
echo "=== keeper.log (last 20) ==="
tail -20 /tmp/keeper.log

echo ""
echo "=== audit trail ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -tAc \"SELECT kind, actor FROM audit_log ORDER BY id\"" 2>/dev/null

kill $KEEPER_PID 2>/dev/null || true
kill $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null || true
echo ""
echo "[test] done"
