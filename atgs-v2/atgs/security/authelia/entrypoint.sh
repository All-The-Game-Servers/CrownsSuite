#!/bin/sh
set -eu

CONFIG_PATH="${AUTHELIA_CONFIG_PATH:-/tmp/authelia-configuration.yml}"
RUNTIME_DIR="${AUTHELIA_RUNTIME_DIR:-/tmp/authelia}"
AUTHELIA_BIN="${AUTHELIA_BIN:-/app/authelia}"

mkdir -p "${RUNTIME_DIR}"

cat > "${CONFIG_PATH}" <<EOF
theme: dark

server:
  address: tcp://0.0.0.0:9091/

log:
  level: info

totp:
  issuer: ATGS Worldwide

authentication_backend:
  file:
    path: /config/users_database.yml
    watch: true
    search:
      email: true
      case_insensitive: true
    password:
      algorithm: argon2
      argon2:
        variant: argon2id
        iterations: 3
        memory: 65536
        parallelism: 4
        key_length: 32
        salt_length: 16

access_control:
  default_policy: deny
  rules:
    - domain: ${ADMIN_DOMAIN}
      policy: two_factor

session:
  name: atgs_session
  same_site: lax
  expiration: 1h
  inactivity: 15m
  remember_me: 7d
  cookies:
    - domain: ${SESSION_DOMAIN}
      authelia_url: https://${AUTH_DOMAIN}
      default_redirection_url: https://${ADMIN_DOMAIN}

regulation:
  max_retries: 5
  find_time: 10m
  ban_time: 1h

storage:
  local:
    path: ${RUNTIME_DIR}/db.sqlite3

notifier:
  filesystem:
    filename: ${RUNTIME_DIR}/notification.txt
EOF

exec "${AUTHELIA_BIN}" --config "${CONFIG_PATH}"
