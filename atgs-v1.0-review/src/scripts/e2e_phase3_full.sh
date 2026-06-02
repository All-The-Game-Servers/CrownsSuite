#!/bin/bash
# Phase 3 full path verification.
#
# Flow:
#  1. Build all binaries (central, relay, keeper, fake_minecraft, fake_client).
#  2. Bootstrap CA, migrate DB, mint relay cert.
#  3. Start Central, relay, keeper (fake docker).
#  4. Create instance with hostname=lowlight.mine.bz.
#  5. Verify routing delta reached relay cache.
#  6. Start fake_minecraft on the port the fake runtime assigned.
#  7. fake_client connects to relay's ingress port, sends handshake.
#  8. Verify fake_client reads "ATGS_PHASE3_OK" marker end-to-end.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs-review

pkill -9 -f "bin/(central|keeper|relay)" 2>/dev/null || true
pkill -9 -f "fake_(minecraft|client)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/relay-a-state /tmp/central.log /tmp/keeper.log /tmp/relay.log /tmp/fakemc.log

# Fresh DB
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1

# Build
rm -rf bin && mkdir bin
go build -o bin/central ./central/cmd/central
go build -o bin/keeper ./keeper/cmd/keeper
go build -o bin/relay ./relay/cmd/relay
go build -o bin/fake_minecraft ./scripts/fake_minecraft.go
go build -o bin/fake_client ./scripts/fake_client.go

# Bootstrap CA + migrations
[ -d .atgs/ca ] || ./bin/central bootstrap-ca >/dev/null 2>&1
./bin/central migrate >/dev/null

# Mint relay cert bundle
./bin/central mint-relay-cert /tmp/relay-a-state >/dev/null

# Start Central
./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
echo "[e2e] central pid: $CENTRAL_PID"
for i in $(seq 1 20); do
    curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1 && break
    sleep 0.5
done
echo "[e2e] central up"

# Start relay
ATGS_RELAY_STATE_DIR=/tmp/relay-a-state \
ATGS_RELAY_CENTRAL_SYNC_URL=wss://127.0.0.1:8443/api/v1/relay-sync \
ATGS_RELAY_INGRESS_ADDR=127.0.0.1:25565 \
ATGS_RELAY_DATA_ADDR=127.0.0.1:7443 \
ATGS_RELAY_PEER_ADDR=127.0.0.1:7444 \
    ./bin/relay serve >/tmp/relay.log 2>&1 &
RELAY_PID=$!
echo "[e2e] relay pid: $RELAY_PID"
sleep 3

# Enroll keeper and wire it to the relay's data channel
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"phase3 e2e"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo "[e2e] keeper token minted"

ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs-review/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
ATGS_KEEPER_RELAY_DATA_URLS=wss://127.0.0.1:7443/ws/data \
ATGS_KEEPER_FAKE_DOCKER=true \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
echo "[e2e] keeper pid: $KEEPER_PID"
sleep 3

KEEPER_ID=$(curl -s http://127.0.0.1:8080/api/v1/keepers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['keepers'][0]['id'])")
echo "[e2e] keeper id: $KEEPER_ID"

# Create an instance WITH hostname
echo ""
echo "=== creating instance with hostname=lowlight.mine.bz ==="
CREATE=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Lowlight SMP",
        "hostname": "lowlight.mine.bz",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
echo "$CREATE" | python3 -m json.tool
INSTANCE_ID=$(echo "$CREATE" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")

sleep 3

# Start it so state flips to running (and fake logs are appended)
curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE_ID/start >/dev/null
sleep 1

# Check what port the fake runtime assigned
HOST_PORT=$(su postgres -c "psql -h /tmp -U atgs -d atgs -tAc \"SELECT host_port FROM instances WHERE instance_id = '$INSTANCE_ID'\"" | tr -d ' ')
echo ""
echo "[e2e] host_port assigned: $HOST_PORT"

echo ""
echo "=== routing_events in DB ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT version, event_type, hostname, host_port FROM routing_events ORDER BY version;'"

# Now start the fake minecraft server on that port so the keeper's stream handler
# can dial it.
./bin/fake_minecraft $HOST_PORT >/tmp/fakemc.log 2>&1 &
FAKEMC_PID=$!
echo "[e2e] fake_minecraft pid: $FAKEMC_PID on port $HOST_PORT"
sleep 1

# Fire the client at the relay's ingress port with our hostname.
echo ""
echo "=== connecting fake client to relay ingress ==="
RESULT=$(./bin/fake_client 127.0.0.1:25565 lowlight.mine.bz 2>/tmp/fakeclient.log || echo "FAIL")
echo "client result: $RESULT"
echo "client log:"
cat /tmp/fakeclient.log

echo ""
echo "=== fake minecraft log ==="
cat /tmp/fakemc.log

echo ""
echo "=== relay.log tail ==="
tail -20 /tmp/relay.log

echo ""
echo "=== keeper.log tail ==="
tail -15 /tmp/keeper.log

echo ""
if [ "$RESULT" = "OK" ]; then
    echo "=== ✓ PHASE 3 END-TO-END PASSED ==="
else
    echo "=== ✗ PHASE 3 END-TO-END FAILED ==="
fi

# Cleanup
kill $FAKEMC_PID $KEEPER_PID $RELAY_PID $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null || true

test "$RESULT" = "OK"
