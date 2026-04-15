#!/usr/bin/env bash
cd /instance
export LD_LIBRARY_PATH=.
echo "[ATGS] Launching Bedrock Dedicated Server..."
exec ./bedrock_server
