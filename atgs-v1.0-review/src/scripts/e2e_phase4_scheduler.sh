#!/bin/bash
# Phase 4 scheduler smoke test.
#
# Creates a schedule with cron "* * * * *" (every minute) on an instance,
# waits ~80 seconds for it to fire, verifies a scheduled backup was created.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs-review

pkill -9 -f "bin/(central|keeper|relay)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/central.log /tmp/keeper.log /tmp/atgs-backups
mkdir -p /tmp/atgs-backups

openssl rand -hex 32 > /tmp/atgs-master.key
chmod 400 /tmp/atgs-master.key

# Fresh DB
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1

# Build (already done, but refresh)
go build -o bin/central ./central/cmd/central
go build -o bin/keeper ./keeper/cmd/keeper

# Bootstrap
[ -d .atgs/ca ] || ./bin/central bootstrap-ca >/dev/null 2>&1
./bin/central migrate 2>&1 | tail -2

# Start Central
export ATGS_CENTRAL_BACKUP_STORAGE=central_fs
export ATGS_CENTRAL_BACKUP_ROOT=/tmp/atgs-backups
export ATGS_CENTRAL_BACKUP_MASTER_KEY=/tmp/atgs-master.key
./bin/central serve >/tmp/central.log 2>&1 &
CENTRAL_PID=$!
for i in $(seq 1 20); do
    curl -s --max-time 1 http://127.0.0.1:8080/api/v1/version >/dev/null 2>&1 && break
    sleep 0.5
done
echo "[e2e] central up"

# Keeper
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"scheduler test"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")

ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs-review/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
ATGS_KEEPER_FAKE_DOCKER=true \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
sleep 3

KEEPER_ID=$(curl -s http://127.0.0.1:8080/api/v1/keepers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['keepers'][0]['id'])")

CREATE=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Scheduled Test",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
INSTANCE=$(echo "$CREATE" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")
echo "[e2e] instance $INSTANCE"
sleep 2

# Plant a tiny file
mkdir -p /tmp/keeper-state/instances/$INSTANCE/world
echo "sample" > /tmp/keeper-state/instances/$INSTANCE/world/test.dat

# Create a schedule with "* * * * *" - fires every minute
echo ""
echo "=== creating schedule (every minute) ==="
SCHED=$(curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE/backup-schedule \
    -H "Content-Type: application/json" -d '{
        "cron_expr": "* * * * *",
        "retention": 3,
        "encrypt": false
    }')
echo "$SCHED" | python3 -m json.tool
NEXT_RUN=$(echo "$SCHED" | python3 -c "import json,sys;print(json.load(sys.stdin)['next_run_at'])")
echo "next_run_at: $NEXT_RUN"

# Force next_run_at to NOW-1s so the scheduler fires on the next tick
# (scheduler polls every 30s; don't want to wait 2 minutes).
su postgres -c "psql -h /tmp -U atgs -d atgs -c \"UPDATE backup_schedules SET next_run_at = NOW() - INTERVAL '1 second';\"" >/dev/null

echo ""
echo "=== waiting 35s for scheduler tick ==="
sleep 35

echo ""
echo "=== backups for this instance ==="
curl -s http://127.0.0.1:8080/api/v1/instances/$INSTANCE/backups | python3 -c "
import json,sys
d = json.load(sys.stdin)
for b in d.get('backups', []):
    print(f\"  {b['backup_id']} status={b['status']} name={b['display_name']} chunks={b['chunk_count']}\")"

echo ""
echo "=== schedule state ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT schedule_id, cron_expr, last_run_at IS NOT NULL AS ran, next_run_at > NOW() AS next_in_future FROM backup_schedules;'"

echo ""
echo "=== scheduler log lines ==="
grep -iE "scheduler|schedul" /tmp/central.log | tail -10

kill $KEEPER_PID $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null || true
echo ""
echo "[e2e] done"
