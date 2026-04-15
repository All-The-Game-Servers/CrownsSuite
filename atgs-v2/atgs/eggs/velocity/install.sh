#!/usr/bin/env bash
set -euo pipefail

VARIANT="${1:-default}"
VERSION="${2:-3.4.0-SNAPSHOT}"
INST_DIR="${3:?Missing instance directory}"
cd "$INST_DIR"

progress() { echo "[PROGRESS] $1 $2"; }
fail()     { echo "[PROGRESS] error $1"; exit 1; }

progress "init" "Installing Velocity proxy..."

# Get latest build
progress "builds" "Fetching Velocity builds for ${VERSION}..."
BUILDS_JSON=$(curl -sf "https://api.papermc.io/v2/projects/velocity/versions/${VERSION}/builds" 2>/dev/null) || true

if [ -n "$BUILDS_JSON" ] && echo "$BUILDS_JSON" | jq -e '.builds[-1]' > /dev/null 2>&1; then
    BUILD=$(echo "$BUILDS_JSON" | jq -r '.builds[-1].build')
    JAR_NAME=$(echo "$BUILDS_JSON" | jq -r '.builds[-1].downloads.application.name')
    URL="https://api.papermc.io/v2/projects/velocity/versions/${VERSION}/builds/${BUILD}/downloads/${JAR_NAME}"
    progress "download" "Downloading Velocity build ${BUILD}..."
    curl -fSL -o velocity.jar "$URL" || fail "Download failed"
else
    # Fallback: try latest from any version
    progress "fallback" "Trying latest Velocity version..."
    VERSIONS_JSON=$(curl -sf "https://api.papermc.io/v2/projects/velocity") || fail "Cannot reach PaperMC API"
    LATEST_VER=$(echo "$VERSIONS_JSON" | jq -r '.versions[-1]')
    BUILDS_JSON=$(curl -sf "https://api.papermc.io/v2/projects/velocity/versions/${LATEST_VER}/builds") || fail "Cannot fetch builds"
    BUILD=$(echo "$BUILDS_JSON" | jq -r '.builds[-1].build')
    JAR_NAME=$(echo "$BUILDS_JSON" | jq -r '.builds[-1].downloads.application.name')
    URL="https://api.papermc.io/v2/projects/velocity/versions/${LATEST_VER}/builds/${BUILD}/downloads/${JAR_NAME}"
    progress "download" "Downloading Velocity ${LATEST_VER} build ${BUILD}..."
    curl -fSL -o velocity.jar "$URL" || fail "Download failed"
fi

progress "setup" "Creating plugin directory..."
mkdir -p plugins

progress "verify" "Velocity installed ($(du -h velocity.jar | cut -f1))"
progress "done" "Velocity proxy ready!"
