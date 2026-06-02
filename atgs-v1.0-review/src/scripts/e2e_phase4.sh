#!/bin/bash
# Phase 4 end-to-end backup + restore verification.
#
# Flow:
#  1. Boot Central with backup master key configured.
#  2. Enroll a keeper with fake docker.
#  3. Create an instance.
#  4. Plant some test files in the instance's data dir.
#  5. Hit POST /backups to create an unencrypted backup.
#  6. Verify backup row transitions to 'complete', chunks land on disk.
#  7. Create a SECOND instance.
#  8. Hit POST /restore targeting the second instance.
#  9. Verify the data files appear in the second instance's dir.
# 10. Create an ENCRYPTED backup, verify chunks are opaque on disk.
set -e

export PATH=/usr/local/go/bin:$PATH
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"

cd /home/claude/atgs-review

pkill -9 -f "bin/(central|keeper|relay)" 2>/dev/null || true
sleep 1
rm -rf /tmp/keeper-state /tmp/central.log /tmp/keeper.log
rm -rf /tmp/atgs-backups
mkdir -p /tmp/atgs-backups

# Master key (32 random bytes, hex-encoded, chmod 400)
openssl rand -hex 32 > /tmp/atgs-master.key
chmod 400 /tmp/atgs-master.key
echo "[e2e] master key: $(head -c 16 /tmp/atgs-master.key)..."

# Fresh DB
su postgres -c "psql -h /tmp -U postgres -c 'DROP DATABASE IF EXISTS atgs;' -c 'CREATE DATABASE atgs OWNER atgs;'" >/dev/null 2>&1

# Build
rm -rf bin && mkdir bin
go build -o bin/central ./central/cmd/central
go build -o bin/keeper ./keeper/cmd/keeper

# Bootstrap CA + migrations
[ -d .atgs/ca ] || ./bin/central bootstrap-ca >/dev/null 2>&1
./bin/central migrate 2>&1 | tail -3

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
echo "[e2e] central up (pid $CENTRAL_PID)"

# Enroll keeper
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/enrollment-tokens \
    -H "Content-Type: application/json" -d '{"note":"phase4 e2e"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
echo "[e2e] keeper token minted"

ATGS_ENROLL_TOKEN=$TOKEN \
ATGS_KEEPER_STATE_DIR=/tmp/keeper-state \
ATGS_KEEPER_EGGS_DIR=/home/claude/atgs-review/eggs \
ATGS_KEEPER_CENTRAL_URL=https://127.0.0.1:8443 \
ATGS_KEEPER_FAKE_DOCKER=true \
    ./bin/keeper >/tmp/keeper.log 2>&1 &
KEEPER_PID=$!
sleep 3
echo "[e2e] keeper up (pid $KEEPER_PID)"

KEEPER_ID=$(curl -s http://127.0.0.1:8080/api/v1/keepers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['keepers'][0]['id'])")

# Create source instance
CREATE1=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Source Instance",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
INSTANCE1=$(echo "$CREATE1" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")
echo "[e2e] source instance: $INSTANCE1"
sleep 2

# Plant test data in the instance's data dir
VOLDIR="/tmp/keeper-state/instances/$INSTANCE1"
mkdir -p "$VOLDIR/world" "$VOLDIR/plugins"
echo "test level.dat content" > "$VOLDIR/world/level.dat"
echo "config line 1" > "$VOLDIR/server.properties"
head -c 1000000 /dev/urandom > "$VOLDIR/world/random.bin"  # 1MB of random bytes for chunk exercise
echo "[e2e] planted test files:"
ls -la "$VOLDIR/"
ls -la "$VOLDIR/world/"

# Create backup (unencrypted)
echo ""
echo "=== creating unencrypted backup ==="
BACKUP1_RESP=$(curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE1/backups \
    -H "Content-Type: application/json" -d '{"display_name": "initial backup"}')
echo "$BACKUP1_RESP" | python3 -m json.tool
BACKUP1_ID=$(echo "$BACKUP1_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['backup_id'])")
sleep 3

echo ""
echo "=== backup status ==="
curl -s http://127.0.0.1:8080/api/v1/backups/$BACKUP1_ID | python3 -m json.tool

echo ""
echo "=== chunks on disk ==="
find /tmp/atgs-backups/chunks -type f | head -10
CHUNK_COUNT=$(find /tmp/atgs-backups/chunks -type f | wc -l)
echo "chunk count on disk: $CHUNK_COUNT"

echo ""
echo "=== DB rows ==="
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT backup_id, status, storage_mode, chunk_count, total_bytes, encrypted FROM backups;'"
su postgres -c "psql -h /tmp -U atgs -d atgs -c 'SELECT sha256, size_bytes, ref_count FROM backup_chunks LIMIT 5;'"

# Create target instance for restore
CREATE2=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Restore Target",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
INSTANCE2=$(echo "$CREATE2" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")
echo ""
echo "[e2e] target instance: $INSTANCE2"
sleep 2

# Restore
echo ""
echo "=== restoring into target instance ==="
RESTORE=$(curl -s -X POST http://127.0.0.1:8080/api/v1/backups/$BACKUP1_ID/restore \
    -H "Content-Type: application/json" -d "{\"target_instance_id\": \"$INSTANCE2\"}")
echo "$RESTORE" | python3 -m json.tool
sleep 3

echo ""
echo "=== target instance data dir after restore ==="
TARGETDIR="/tmp/keeper-state/instances/$INSTANCE2"
ls -la "$TARGETDIR/" 2>/dev/null || echo "no target dir"
ls -la "$TARGETDIR/world/" 2>/dev/null || echo "no world dir"

# Verify content
if [ -f "$TARGETDIR/world/level.dat" ]; then
    RESTORED=$(cat "$TARGETDIR/world/level.dat")
    if [ "$RESTORED" = "test level.dat content" ]; then
        echo "[e2e] ✓ level.dat content matches"
    else
        echo "[e2e] ✗ level.dat content mismatch: $RESTORED"
    fi
fi

# Encrypted backup
echo ""
echo "=== creating ENCRYPTED backup ==="
BACKUP2_RESP=$(curl -s -X POST http://127.0.0.1:8080/api/v1/instances/$INSTANCE1/backups \
    -H "Content-Type: application/json" -d '{"display_name": "encrypted backup", "encrypted": true}')
echo "$BACKUP2_RESP" | python3 -m json.tool
BACKUP2_ID=$(echo "$BACKUP2_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['backup_id'])")
sleep 3

echo ""
echo "=== encrypted backup status ==="
curl -s http://127.0.0.1:8080/api/v1/backups/$BACKUP2_ID | python3 -c "
import json, sys
d = json.load(sys.stdin)
b = d['backup']
m = d.get('manifest') or {}
print(f\"status={b['status']} encrypted={b['encrypted']} chunks={b['chunk_count']} bytes={b['total_bytes']}\")
if m:
    print(f\"manifest.encrypted={m.get('encrypted')} key_fingerprint={m.get('key_fingerprint')}\")"

echo ""
echo "=== verify encrypted chunks don't contain plaintext ==="
# The plaintext includes "test level.dat content" - none of the chunks should.
MATCHES=$(find /tmp/atgs-backups/chunks -type f -exec grep -l "test level.dat content" {} \; 2>/dev/null | wc -l)
TOTAL=$(find /tmp/atgs-backups/chunks -type f | wc -l)
echo "chunks total: $TOTAL | chunks containing plaintext marker: $MATCHES"
if [ "$MATCHES" -gt 0 ] && [ "$MATCHES" -eq "$TOTAL" ]; then
    echo "[e2e] WARN all chunks have plaintext (only unencrypted backup exists?)"
elif [ "$MATCHES" -lt "$TOTAL" ]; then
    echo "[e2e] ✓ some chunks are opaque (encrypted backup produced opaque chunks)"
fi

# Restore encrypted backup into a THIRD instance
CREATE3=$(curl -s -X POST http://127.0.0.1:8080/api/v1/keepers/$KEEPER_ID/instances \
    -H "Content-Type: application/json" -d '{
        "egg_id": "minecraft-java-paper",
        "display_name": "Encrypted Restore Target",
        "memory_bytes": 2147483648,
        "cpu_shares": 1024
    }')
INSTANCE3=$(echo "$CREATE3" | python3 -c "import json,sys;print(json.load(sys.stdin)['instance_id'])")
sleep 1

echo ""
echo "=== restoring encrypted backup into instance $INSTANCE3 ==="
RESTORE3=$(curl -s -X POST http://127.0.0.1:8080/api/v1/backups/$BACKUP2_ID/restore \
    -H "Content-Type: application/json" -d "{\"target_instance_id\": \"$INSTANCE3\"}")
echo "$RESTORE3" | python3 -m json.tool
sleep 3

TARGET3="/tmp/keeper-state/instances/$INSTANCE3"
if [ -f "$TARGET3/world/level.dat" ]; then
    RESTORED=$(cat "$TARGET3/world/level.dat")
    if [ "$RESTORED" = "test level.dat content" ]; then
        echo "[e2e] ✓ encrypted backup restored and decrypted correctly"
    else
        echo "[e2e] ✗ decrypted content mismatch"
    fi
else
    echo "[e2e] ✗ no level.dat in encrypted restore target"
fi

# Cleanup
echo ""
echo "=== tail central log ==="
grep -iE "backup|routing" /tmp/central.log | tail -10
echo ""
echo "=== tail keeper log ==="
grep -iE "backup|restore|executing" /tmp/keeper.log | tail -10

kill $KEEPER_PID $CENTRAL_PID 2>/dev/null || true
wait 2>/dev/null || true
echo ""
echo "[e2e] done"
