# ATGS v1.1 Validation Checklist

Use this checklist before calling a build v1.1-ready.

## Central

- [ ] Postgres is reachable
- [ ] `central setup` completes from a fresh machine
- [ ] non-root `central setup` warns clearly before a systemd install attempt
- [ ] `central serve` starts and `GET /api/v1/version` responds

## Keeper

- [ ] `keeper init` works with `https://host:8443`
- [ ] `keeper init` works with `https://host:8443/`
- [ ] Keeper enrolls and appears in `GET /api/v1/keepers`
- [ ] Keeper startup works on Linux
- [ ] Keeper startup works on Windows using the generated `keeper.env`

## Progenitor

- [ ] Progenitor connects to a real Central deployment
- [ ] Progenitor can list keepers
- [ ] Progenitor can list instances
- [ ] Progenitor can create and start a Paper instance
- [ ] Progenitor can create and start a Fabric instance
- [ ] Progenitor `Operations` can load logs for a running instance
- [ ] Progenitor `Operations` can send a bounded console line to a running instance
- [ ] Progenitor `Operations` shows recent task outcomes and audit history

## Backups and schedules

- [ ] `scripts/e2e_phase4.sh` passes against a fresh Central + Keeper setup
- [ ] Progenitor can create an unencrypted backup
- [ ] Progenitor can create an encrypted backup when the Central master key is configured
- [ ] Progenitor can restore a completed backup into a chosen target instance
- [ ] Progenitor can delete a backup
- [ ] Progenitor can create and list schedules

## Java relay

- [ ] Relay syncs routing from Central
- [ ] Paper instance create returns a non-zero `host_port`
- [ ] Paper instance start returns a non-zero `host_port`
- [ ] hostname-based routing is present on the relay
- [ ] a Java client can join through Relay ingress and receive bytes back

## Fabric relay

- [ ] Fabric instance create returns a non-zero `host_port`
- [ ] Fabric instance start returns a non-zero `host_port`
- [ ] hostname-based routing is present on the relay
- [ ] a Fabric client can join through Relay ingress and receive bytes back

## Cross-relay

- [ ] Keeper attached to relay B can be reached by a player entering through relay A
- [ ] reconnect behavior does not leave stale keeper affinity behind
- [ ] disconnect cleanup works

## Bedrock relay

- [ ] Bedrock instance create returns an assigned `public_port`
- [ ] Bedrock instance create or start returns a non-zero keeper-local `host_port`
- [ ] relay routing is present for the assigned Bedrock public UDP port
- [ ] a Bedrock client can connect through Relay without Keeper port forwarding
- [ ] Bedrock cross-relay forwarding works when the player enters through a non-owner relay
- [ ] Bedrock idle-session cleanup does not leave stale routes or leaked listeners

## Release package

- [ ] `scripts/e2e_phase3_local.ps1` passes in both single-relay and cross-relay mode
- [ ] `dist/` contains the four expected binaries
- [ ] `deploy/`, `docs/`, `eggs/`, and `migrations/` are included
- [ ] release docs only mention eggs that actually ship
- [ ] release package contains `minecraft-java-paper`, `minecraft-java-fabric`, and `minecraft-bedrock`
- [ ] release docs clearly describe Java hostname routing and Bedrock UDP public-port routing
