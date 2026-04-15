#!/usr/bin/env bash
set -euo pipefail

VARIANT="${1:-default}"
VERSION="${2:-LATEST}"
INST_DIR="${3:?Missing instance directory}"
cd "$INST_DIR"

progress() { echo "[PROGRESS] $1 $2"; }
fail()     { echo "[PROGRESS] error $1"; exit 1; }

progress "init" "Installing Minecraft Bedrock Dedicated Server..."

# Bedrock server download page requires accepting terms
# The download URL follows a predictable pattern
progress "download" "Downloading Bedrock server..."
BDS_URL="https://minecraft.azureedge.net/bin-linux/bedrock-server-1.21.51.02.zip"

curl -fSL -o bedrock-server.zip "$BDS_URL" \
    -H "Accept: application/zip" \
    || fail "Download failed — Mojang may have changed the URL. Check https://www.minecraft.net/en-us/download/server/bedrock"

progress "extract" "Extracting server files..."
unzip -o bedrock-server.zip || fail "Extraction failed"
rm -f bedrock-server.zip

progress "permissions" "Setting executable permissions..."
chmod +x bedrock_server

progress "done" "Bedrock Dedicated Server installed!"
