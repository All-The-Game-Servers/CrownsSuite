#!/usr/bin/env bash
set -euo pipefail

VARIANT="${1:-jar}"
VERSION="${2:-custom}"
INST_DIR="${3:?Missing instance directory}"
cd "$INST_DIR"

progress() { echo "[PROGRESS] $1 $2"; }

progress "init" "Setting up Generic Application (${VARIANT})..."

case "$VARIANT" in
    jar)
        progress "setup" "Creating Java application structure..."
        mkdir -p config logs
        cat > README.txt << 'EOF'
ATGS Generic Java Runner
=========================
Upload your .jar file to this directory via the file manager.
The start script will automatically find and run the first .jar it finds.
Set MIN_RAM and MAX_RAM in instance settings to control memory.
EOF
        progress "done" "Ready! Upload your .jar file via the file manager."
        ;;
    script)
        progress "setup" "Creating script runner structure..."
        mkdir -p scripts logs
        cat > scripts/app.sh << 'SCRIPT'
#!/usr/bin/env bash
echo "Hello from ATGS Generic Runner!"
echo "Replace this script with your own application."
echo "This file is at /instance/scripts/app.sh"
sleep infinity
SCRIPT
        chmod +x scripts/app.sh
        progress "done" "Ready! Edit scripts/app.sh via the file manager."
        ;;
    node)
        progress "setup" "Creating Node.js application structure..."
        mkdir -p src logs
        cat > package.json << 'PKG'
{
  "name": "atgs-app",
  "version": "1.0.0",
  "main": "src/index.js",
  "scripts": { "start": "node src/index.js" }
}
PKG
        cat > src/index.js << 'APP'
const http = require('http');
const PORT = process.env.PORT || 8080;
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('Hello from ATGS!\n');
});
server.listen(PORT, () => console.log(`Listening on :${PORT}`));
APP
        progress "done" "Ready! Edit src/index.js via the file manager."
        ;;
    *)
        progress "error" "Unknown variant: ${VARIANT}"
        exit 1
        ;;
esac
