#!/usr/bin/env bash
# ==============================================================
#  ATGS Runner — Generic Instance Entrypoint
#  A product of XKStudios
#
#  This entrypoint is game-agnostic. It:
#  1. Fixes volume permissions (root → runner)
#  2. Writes any EULA/acceptance files if flagged
#  3. Validates that a start script exists
#  4. Drops to non-root user via gosu
#  5. Executes the panel-generated start.sh
# ==============================================================
set -e

LOG="[ATGS Runner]"
log()  { echo "$LOG $*"; }
warn() { echo "$LOG WARN: $*" >&2; }
die()  { echo "$LOG FATAL: $*" >&2; exit 1; }

write_status() {
    echo "{\"phase\":\"$1\",\"message\":\"$2\",\"time\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /instance/.atgs-status 2>/dev/null || true
}

# ── Phase 1: Environment ─────────────────────────────────────
log "━━━ ATGS Worldwide Container System ━━━"
log "Instance directory: /instance"
log "Running as: $(whoami) (UID $(id -u))"
log "EULA: ${EULA:-not set} | MIN_RAM: ${MIN_RAM:-1G} | MAX_RAM: ${MAX_RAM:-2G}"
write_status "init" "Starting instance"

# ── Phase 2: Fix permissions ─────────────────────────────────
if [ "$(id -u)" -eq 0 ]; then
    log "Fixing volume ownership → runner:runner (1000:1000)..."
    chown -R runner:runner /instance
    write_status "permissions" "Fixed"
fi

# ── Phase 3: EULA / acceptance files ─────────────────────────
if [ "${EULA:-false}" = "true" ] && [ ! -f /instance/eula.txt ] || [ "${EULA:-false}" = "true" ]; then
    echo "eula=true" > /instance/eula.txt
    [ "$(id -u)" -eq 0 ] && chown runner:runner /instance/eula.txt
    log "EULA accepted."
fi
write_status "configured" "Configuration applied"

# ── Phase 4: Validate files ──────────────────────────────────
if [ ! -f /instance/start.sh ]; then
    # Check for any jar as fallback
    JAR=$(find /instance -maxdepth 1 -name '*.jar' -type f | head -1)
    if [ -z "$JAR" ]; then
        warn "No start.sh or .jar found. Instance not yet installed."
        write_status "waiting" "Waiting for installation"
        log "Sleeping until files are provided..."
        sleep infinity
    fi
    log "No start.sh found but jar exists: $(basename "$JAR")"
    log "Creating fallback start script..."
    cat > /instance/start.sh << SCRIPT
#!/bin/bash
cd /instance
exec java -Djava.awt.headless=true -Xms\${MIN_RAM:-1G} -Xmx\${MAX_RAM:-2G} -jar "$(basename "$JAR")" --nogui
SCRIPT
    [ "$(id -u)" -eq 0 ] && chown runner:runner /instance/start.sh
fi

chmod +x /instance/start.sh
log "Validated: start.sh ready."
write_status "starting" "Launching instance"

# ── Phase 5: Drop privileges and launch ──────────────────────
if [ "$(id -u)" -eq 0 ]; then
    log "Dropping to runner user..."
    exec gosu runner /instance/start.sh
else
    exec /instance/start.sh
fi
