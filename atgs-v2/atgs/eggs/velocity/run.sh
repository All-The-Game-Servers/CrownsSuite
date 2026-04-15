#!/usr/bin/env bash
cd /instance
echo "[ATGS] Starting Velocity proxy..."
exec java -Djava.awt.headless=true -Xms${MIN_RAM:-512M} -Xmx${MAX_RAM:-1G} \
  -XX:+UseG1GC -XX:G1HeapRegionSize=4M \
  -XX:+UnlockExperimentalVMOptions -XX:+ParallelRefProcEnabled \
  -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15 \
  -jar velocity.jar
