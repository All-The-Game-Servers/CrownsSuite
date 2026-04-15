# ATGS v2 Linux Minecraft Stack

ATGS v2 is now a Linux-first Minecraft hosting stack with a hardened Docker trust boundary:

- `gateway` is the only default public gameplay entrypoint on `25577/tcp`
- `caddy` is the public admin path on `80/443`
- `panel` is internal-only and rejects direct admin access unless it came through the HTTPS proxy
- `agent` stays internal on `9393` and talks to Docker through a socket proxy instead of the raw host socket
- owner bootstrap is fixed to `KrispKlank` through the panel secret seed
- existing ATGS `.tar.gz` backups still import on Linux through the panel
- new exports use an ATGS v2 package with files, config, and manifest metadata

## Linux-Only Support

This v2 stack is designed for Linux hosts. Windows deployment is no longer a supported target for the hardened production path.

## Public and Private Ports

Open to the internet:

- `80/tcp` and `443/tcp` for the admin site behind Caddy + Authelia
- `25577/tcp` for Velocity / Java players

Closed to the internet:

- `8080/tcp` panel
- `9393/tcp` agent
- backend Minecraft container port
- RCON
- Docker API socket
- Bedrock `19132/udp` unless you explicitly enable Geyser

## Security Model

The production path is:

1. Internet traffic reaches `caddy`
2. `caddy` forwards admin traffic to `panel`
3. `panel` enforces proxy-only access, secure cookies, brute-force throttling, lockouts, and optional admin IP allowlisting
4. `panel` talks to `agent` on the internal control network
5. `agent` talks to the host Docker daemon through `socket-proxy`
6. `gateway` wakes the sleeping backend through internal panel endpoints

Important host-protection defaults:

- The raw Docker socket is mounted only into `socket-proxy`
- `panel` has no Docker socket access
- `agent` is not host-published and uses `DOCKER_HOST=tcp://socket-proxy:2375`
- control, runtime, and Docker management traffic are split across separate Docker networks
- hardened services use `no-new-privileges` and dropped Linux capabilities where practical
- gateway, Caddy, and socket-proxy use read-only root filesystems
- panel login has per-IP and per-user lockouts
- optional `ADMIN_ALLOWED_CIDRS` restricts which source IPs can reach the panel

## First-Time Setup on Linux

1. Copy `.env.example` to `.env` and set:
   - `ATGS_UID`
   - `ATGS_GID`
   - `ADMIN_DOMAIN`
   - `SESSION_DOMAIN`
   - `ACME_EMAIL`
   - optional `ADMIN_ALLOWED_CIDRS`
   Run `id -u` and `id -g` on the Linux host if the service account is not the default `1000:1000`.
2. Replace every file under `data/control/secrets/` with strong random values.
3. The primary ATGS owner is always `KrispKlank`. Set `data/control/secrets/panel-admin-password` to the password you want seeded on first boot.
4. Create the runtime directories:

```bash
mkdir -p data/control/authelia \
  data/control/gateway \
  data/imports \
  data/instances/main/files \
  data/instances/main/backups
```

5. Build and start the hardened stack:

```bash
docker compose -f docker-compose.v2.yml build runner-base panel agent gateway caddy
docker compose -f docker-compose.v2.yml up -d
```

Admin access then lives at `https://$ADMIN_DOMAIN`.

## Owner Login Bootstrap

ATGS panel owner bootstrap is fixed to:

- username: `KrispKlank`
- password source: `data/control/secrets/panel-admin-password`

Behavior:

- first boot with an empty panel DB creates owner `KrispKlank`
- later boots keep the stored owner password unless you explicitly reset it
- the panel includes a `Reset Owner Password From Secret` action for reseeding from the mounted Linux secret

## Panel Protections

The default public admin path uses the ATGS panel's own protections behind Caddy:

- HTTPS-only access through `caddy`
- proxy-secret enforcement so the panel is not meant to be reached directly
- secure cookies
- per-IP and per-user login lockouts
- optional IP allowlisting with `ADMIN_ALLOWED_CIDRS`

Example allowlist:

```bash
ADMIN_ALLOWED_CIDRS=203.0.113.10,198.51.100.0/24
```

If you ever want the external auth layer back later, run the optional profile explicitly:

```bash
docker compose -f docker-compose.v2.yml --profile auth up -d authelia
```

## Backups and Restore

ATGS v2 keeps backup compatibility:

- New exports are portable `.tar.gz` ATGS v2 packages with:
  - `files/`
  - `config/server.json`
  - `config/addons.json`
  - `manifest.json`
- Old ATGS `.tar.gz` archives can still be uploaded through the panel on Linux
- Restore is format-aware and detects whether the archive is legacy or ATGS v2
- Secrets are intentionally excluded from exports and must already exist on the destination Linux host
- The agent restores into staging first, validates the package, and rolls back if restore fails

## Runtime Notes

- The backend Minecraft server sleeps after the idle grace window when no players remain
- Velocity stays online and wakes the backend on join
- RCON is kept internal to the Docker runtime network and is never host-published
- Geyser / Bedrock remains opt-in and only publishes `19132/udp` when enabled

## Verification Checklist

After deployment, verify:

```bash
docker compose -f docker-compose.v2.yml ps
docker compose -f docker-compose.v2.yml logs -f caddy authelia panel agent gateway
```

And confirm the following from Docker:

- `8080` is not published
- `9393` is not published
- only `80`, `443`, and `25577` are host-visible by default
- the panel security card shows auth proxy active, socket proxy active, and agent internal-only
