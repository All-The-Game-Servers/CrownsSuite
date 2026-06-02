# ATGS Operations Guide

Day-2 operations for a running ATGS deployment.

## Making your first game server

After Central is running and a Keeper is enrolled:

1. Log into Progenitor
2. Click "Instances" → "New Instance"
3. Pick an egg:
   - **Minecraft Java (Paper)**: port 25565 TCP, clients use Java Edition
   - **Minecraft Bedrock**: port 19132 UDP, clients use mobile/console/Windows 10 Bedrock
4. Name it
5. Memory limit (2GB default, 4-8GB for modded)
6. Click "Create"

The keeper pulls the Docker image (first time only, few minutes) and starts the server. Players connect to the keeper's public IP on the game port.

## Backing up and restoring

**Manual backup**: Progenitor → Instances → pick instance → "Backup Now". Creates a timestamped backup using Central's chunk storage.

**Scheduled backup**: Progenitor → Instances → "Backup Schedule". Cron-style expression (e.g. `0 3 * * *` for 3am daily). Retention policy (e.g. keep last 7 daily + last 4 weekly).

**Restore**: Progenitor → Backups → pick backup → "Restore to Instance". The instance is stopped, data replaced from the backup, and started.

## Adding another keeper

```bash
# On Central:
./central-linux-amd64 mint-enrollment-token
# Copy the token.

# On the new keeper host:
./keeper-linux-amd64 init
# Paste the token when prompted. Start the keeper.
```

## Rotating keeper credentials

Keepers receive certs at enrollment that are valid for 1 year. To rotate early:

1. In Progenitor → Keepers → select keeper → "Revoke"
2. Provide a reason (logged in the audit trail)
3. The keeper receives a `keeper.revoke` task, wipes its identity, and exits
4. Its cert is added to the CRL; any attempt to reconnect is rejected at TLS
5. Mint a new enrollment token and re-enroll the host

## Monitoring

Central exposes these endpoints:
- `GET /api/v1/version` — version info, no auth
- `GET /api/v1/keepers` — admin API, list keepers with last-seen times
- `GET /api/v1/admin/audit` — audit log with optional keeper filter

Set `ATGS_CENTRAL_AUDIT_SYSLOG_PATH=/var/log/atgs-audit.log` to stream audit events for SIEM ingestion.

## Upgrading

1. Back up the Postgres database: `pg_dump atgs > atgs-backup.sql`
2. Stop Central: `systemctl stop atgs-central`
3. Replace the binary with the new version
4. Run `./central setup` — it detects the existing DB and only applies new migrations
5. Restart: `systemctl start atgs-central`
6. Keepers reconnect automatically; no action needed unless protocol version bumped

## Common issues

**"Postgres connection refused"**: ensure Postgres is listening, the database exists, and `ATGS_CENTRAL_DATABASE_URL` is correct.

**"Keeper can't connect"**: check that TCP port 8443 is reachable from the keeper host, and that the keeper's `ATGS_KEEPER_CENTRAL_URL` matches the central's DNS name or IP.

**"Game server won't start"**: check keeper logs (`journalctl -u atgs-keeper` or Docker logs). Usually either a bad egg config, out-of-memory, or Docker daemon permissions.

**"Bedrock players can't connect"**: check host firewall allows inbound UDP 19132. Bedrock requires direct UDP connectivity to the keeper (v1.0 limitation; v1.1 adds RakNet relay).

## Env vars cheat sheet

### Central
- `ATGS_CENTRAL_DATABASE_URL` — required, Postgres DSN
- `ATGS_CENTRAL_ADMIN_ADDR` — admin HTTP listen, default `127.0.0.1:8080`
- `ATGS_CENTRAL_KEEPER_ADDR` — mTLS listen for keepers/relays, default `127.0.0.1:8443`
- `ATGS_CENTRAL_CA_DIR` — where CA + signing key live, default `./.atgs/ca`
- `ATGS_CENTRAL_BACKUP_ROOT` — backup storage, default `./.atgs/backups`
- `ATGS_CENTRAL_ADMIN_EMAIL` / `ATGS_CENTRAL_ADMIN_PASSWORD` — first-boot admin bootstrap
- `ATGS_CENTRAL_REQUIRE_SIGNED_TASKS` — flip to `true` to enforce Ed25519 signing (after all keepers have re-enrolled)
- `ATGS_CENTRAL_AUDIT_SYSLOG_PATH` — path for audit event streaming

### Keeper
- `ATGS_KEEPER_CENTRAL_URL` — required, `https://central:8443`
- `ATGS_ENROLL_TOKEN` — one-time token for first-run enrollment
- `ATGS_KEEPER_STATE_DIR` — identity + local DB, default `~/.atgs-keeper`
- `ATGS_KEEPER_EGGS_DIR` — egg manifests, default `./eggs`
- `ATGS_KEEPER_DATA_ROOT` — per-instance data volumes, default `<state>/instances`
- `ATGS_KEEPER_INSECURE_TLS` — set to `false` in production

## Production deployment checklist

- [ ] Postgres on dedicated instance with backups
- [ ] `ATGS_CENTRAL_DEV=false`
- [ ] `ATGS_CENTRAL_ADMIN_PASSWORD` unset after first-boot
- [ ] `ATGS_KEEPER_INSECURE_TLS=false` (keeper trusts central's CA properly)
- [ ] Reverse proxy (nginx/Caddy) in front of `ATGS_CENTRAL_ADMIN_ADDR` with real TLS
- [ ] Firewall: 8443 TCP from trusted IPs, 25565 TCP + 19132 UDP for game players
- [ ] Systemd units installed for central and keeper
- [ ] Audit log streaming to SIEM
- [ ] Automated Postgres backups
- [ ] Keeper cert rotation schedule (yearly is default)
