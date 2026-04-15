#!/bin/sh
set -eu

PANEL_PROXY_SECRET="$(cat /run/secrets/atgs/panel-proxy-secret)"
AUTH_PROXY_ENABLED="${AUTH_PROXY_ENABLED:-true}"

if [ "${AUTH_PROXY_ENABLED}" = "true" ]; then
cat > /config/Caddyfile <<EOF
{
	email ${ACME_EMAIL}
}

${AUTH_DOMAIN} {
	encode zstd gzip
	reverse_proxy authelia:9091
}

${ADMIN_DOMAIN} {
	encode zstd gzip

	forward_auth authelia:9091 {
		uri /api/authz/forward-auth
		copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
	}

	reverse_proxy panel:8080 {
		header_up X-ATGS-Proxy-Secret ${PANEL_PROXY_SECRET}
		header_up X-Forwarded-Proto {scheme}
		header_up X-Forwarded-Host {host}
		header_up X-Forwarded-For {remote_host}
	}
}
EOF
else
cat > /config/Caddyfile <<EOF
{
	email ${ACME_EMAIL}
}

${ADMIN_DOMAIN} {
	encode zstd gzip

	reverse_proxy panel:8080 {
		header_up X-ATGS-Proxy-Secret ${PANEL_PROXY_SECRET}
		header_up X-Forwarded-Proto {scheme}
		header_up X-Forwarded-Host {host}
		header_up X-Forwarded-For {remote_host}
	}
}
EOF
fi

exec caddy run --config /config/Caddyfile --adapter caddyfile
