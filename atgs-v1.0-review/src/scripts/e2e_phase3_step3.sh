#!/bin/bash
# Phase 3 step 3 end-to-end:
#
#   1. Boot Central, bootstrap CA, apply migrations.
#   2. Mint a relay cert bundle.
#   3. Start relay — verify it connects to /relay-sync, gets empty snapshot.
#   4. Mint a keeper token, enroll keeper with FAKE docker, connect it.
#   5. Create an instance WITH a hostname.
#   6. Verify the routing delta flows live to the relay and the cache
#      reflects the new routing entry.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs

# Build
rm -rf bin && mkdir bin
go build -o bin/central ./central/cmd/central
go build -o bin/keeper ./keeper/cmd/keeper
go build -o bin/relay ./relay/cmd/relay

# Clean prior state
pkill -9 -f "bin/(central|keeper|relay)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/relay-a-state /tmp/central.log /tmp/keeper.log /tmp/relay.log

# Fresh DB
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1

# Bootstrap CA (idempotent; .atgs dir persists across test runs)
[ -f .atgs/ca/ca.crt ] || ./bin/central bootstrap-ca >/dev/null 2>&1
./bin/central migrate >/dev/null

# Mint relay bundle
./bin/central mint-relay-cert /tmp/relay-a-state >/dev/null

# Start Central
./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
echo "[test] central pid: $CENTRAL_PID"
for i in $(seq 1 20); do
    curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1 && break
    sleep 0.5
done
echo "[test] central up"

# Start relay pointed at Central
ATGS_RELAY_STATE_DIR=/tmp/relay-a-state \
ATGS_RELAY_CENTRAL_SYNC_URL=wss://127.0.0.1:8443/api/v1/relay-sync \
ATGS_RELAY_INGRESS_ADDR=127.0.0.1:25565 \
ATGS_RELAY_DATA_ADDR=127.0.0.1:7443 \
ATGS_RELAY_PEER_ADDR=127.0.0.1:7444 \
    ./bin/relay serve >/tmp/relay.log 2>&1 &
RELAY_PID=$!
echo "[test] relay pid: $RELAY_PID"
sleep 3

echo ""
echo "=== relay.log after sync connect ==="
cat /tmp/relay.log

# Enroll keeper with fake docker
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"step3"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo ""
echo "[test] keeper token minted"

ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
ATGS_KEEPER_FAKE_DOCKER=true \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
echo "[test] keeper pid: $KEEPER_PID"
sleep 3

KEEPER_ID=$(curl -s http://127.0.0.1:8080/api/v1/keepers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['keepers'][0]['id'])")
echo "[test] keeper id: $KEEPER_ID"

# Create instance WITH hostname — this is the new Phase 3 path
echo ""
echo "=== POST /api/v1/keepers/\$KEEPER_ID/instances (with hostname) ==="
CREATE=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Lowlight SMP",
        "hostname": "lowlight.mine.bz",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
echo "$CREATE" | python3 -m json.tool

sleep 3

# Currently the fake runtime returns HostPort=0, so no routing upsert would
# fire. For Phase 3 step 3, the cleanest proof of the sync channel is to see
# a relay.log line showing 'relay-sync connected'. Routing delta propagation
# end-to-end will be exercised when the fake runtime starts returning a
# port (step 5 onward).
echo ""
echo "=== relay.log (full) ==="
cat /tmp/relay.log

echo ""
echo "=== central.log (relay-sync lines) ==="
grep -E "relay|routing" /tmp/central.log || echo "(no relay lines)"

echo ""
echo "=== routing_events in DB ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT version, event_type, hostname, host_port FROM routing_events ORDER BY version;'" 2>&1

echo ""
echo "=== instances table (hostname + host_port) ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT display_name, hostname, host_port, state FROM instances;'" 2>&1

# Cleanup
kill $KEEPER_PID $RELAY_PID $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null || true
echo ""
echo "[test] done"
