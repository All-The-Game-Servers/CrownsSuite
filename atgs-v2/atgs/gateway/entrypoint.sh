#!/usr/bin/env sh
set -eu

VELOCITY_HOME="${VELOCITY_HOME:-/data/gateway}"
DIST_PLUGIN_JAR="/opt/atgs/atgs-gateway-plugin.jar"
mkdir -p "${VELOCITY_HOME}/plugins"
mkdir -p "${VELOCITY_HOME}/logs"

read_secret() {
  var_name="$1"
  eval "value=\${$var_name:-}"
  eval "file_path=\${${var_name}_FILE:-}"
  if [ -n "${file_path:-}" ] && [ -f "${file_path}" ]; then
    value="$(cat "${file_path}")"
  fi
  printf '%s' "${value}"
}

GATEWAY_SHARED_SECRET="$(read_secret GATEWAY_SHARED_SECRET)"
FORWARDING_SECRET_VALUE="$(read_secret FORWARDING_SECRET)"

if [ ! -f "${VELOCITY_HOME}/velocity.jar" ]; then
  VERSION="${VELOCITY_VERSION:-3.4.0-SNAPSHOT}"
  if [ "$VERSION" = "latest" ]; then
    VERSION="$(curl -sf https://api.papermc.io/v2/projects/velocity | jq -r '.versions[-1]')"
  fi
  BUILDS="$(curl -sf "https://api.papermc.io/v2/projects/velocity/versions/${VERSION}/builds")"
  BUILD="$(echo "$BUILDS" | jq -r '.builds[-1].build')"
  JAR_NAME="$(echo "$BUILDS" | jq -r '.builds[-1].downloads.application.name')"
  curl -fSL -o "${VELOCITY_HOME}/velocity.jar" "https://api.papermc.io/v2/projects/velocity/versions/${VERSION}/builds/${BUILD}/downloads/${JAR_NAME}"
fi

if [ -f "${DIST_PLUGIN_JAR}" ] && [ ! -f "${VELOCITY_HOME}/plugins/atgs-gateway-plugin.jar" ]; then
  cp "${DIST_PLUGIN_JAR}" "${VELOCITY_HOME}/plugins/atgs-gateway-plugin.jar"
fi

cat > "${VELOCITY_HOME}/velocity.toml" <<EOF
config-version = "2.7"
bind = "0.0.0.0:${GATEWAY_PORT:-25577}"
motd = "${GATEWAY_MOTD:-ATGS Gateway}"
show-max-players = ${MAX_PLAYERS:-50}
online-mode = true
force-key-authentication = true
player-info-forwarding-mode = "modern"

[servers]
main = "${BACKEND_ADDRESS:-atgs-minecraft:25565}"
try = ["main"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
connection-timeout = 5000
read-timeout = 30000
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = false
log-player-connections = true
accepts-transfers = false

[query]
enabled = false
port = ${GATEWAY_PORT:-25577}
EOF

printf '%s' "${FORWARDING_SECRET_VALUE:-change-forwarding-secret}" > "${VELOCITY_HOME}/forwarding.secret"

cat > "${VELOCITY_HOME}/plugins/atgs-gateway.properties" <<EOF
panelBaseUrl=${PANEL_BASE_URL:-http://panel:8080}
sharedSecret=${GATEWAY_SHARED_SECRET:-change-me-too}
pollMs=${WAKE_POLL_MS:-2000}
timeoutMs=${WAKE_TIMEOUT_MS:-45000}
EOF

cd "${VELOCITY_HOME}"
exec java -Xms128M -Xmx256M -jar "${VELOCITY_HOME}/velocity.jar"
