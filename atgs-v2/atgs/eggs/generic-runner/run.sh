#!/usr/bin/env bash
# ==============================================================
#  Generic Runner — Start Script
#  Auto-detects application type and launches it.
# ==============================================================
set -e
cd /instance

# If package.json exists, run with Node
if [ -f package.json ]; then
    echo "[ATGS] Detected Node.js application"
    if [ -f node_modules/.package-lock.json ] 2>/dev/null; then
        echo "[ATGS] Dependencies already installed"
    else
        echo "[ATGS] Installing npm dependencies..."
        npm install --production 2>/dev/null || true
    fi
    exec node ${APP_ENTRY:-src/index.js} ${APP_ARGS:-}
fi

# If a .jar file exists, run with Java
JAR=$(find /instance -maxdepth 1 -name '*.jar' -type f | head -1)
if [ -n "$JAR" ]; then
    echo "[ATGS] Detected Java application: $(basename "$JAR")"
    exec java -Xms${MIN_RAM:-512M} -Xmx${MAX_RAM:-1G} -jar "$JAR" ${APP_ARGS:-}
fi

# If scripts/app.sh exists, run it
if [ -f scripts/app.sh ]; then
    echo "[ATGS] Detected shell script: scripts/app.sh"
    chmod +x scripts/app.sh
    exec bash scripts/app.sh ${APP_ARGS:-}
fi

# If a custom start command is defined via env
if [ -n "${START_CMD:-}" ]; then
    echo "[ATGS] Running custom command: ${START_CMD}"
    exec bash -c "${START_CMD}"
fi

echo "[ATGS] No application found. Upload files via the panel."
echo "[ATGS] Supported: .jar files, package.json, scripts/app.sh"
sleep infinity
