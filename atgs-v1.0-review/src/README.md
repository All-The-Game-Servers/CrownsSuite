# ATGS v1 Source Tree

ATGS, short for All The Game Servers, is XKStudios' federated game-server hosting platform. In this repo, one Linux Central control plane manages one or more Keepers that run game servers on their own hardware, a Relay that fronts player traffic, and a Progenitor desktop app used by operators.

This source tree is the release source of truth. If the packaged release says something different from this README or the files under `docs/`, trust the source tree and rebuild the package from here.

## What is actually in v1.2 today

- Central
  - Go control plane
  - Postgres-backed
  - keeper enrollment, mTLS identities, task dispatch, backups, relay routing sync
  - workspace-first ownership model with default-workspace migration
  - human account sessions plus workspace memberships and capability flags
  - bounded remote file-management tasks scoped to each instance data root
- Keeper
  - Go daemon
  - Docker-backed game runtime
  - Linux and Windows binaries
  - fake-docker mode for relay and API smoke tests
- Relay
  - Go Java and Bedrock ingress data plane
  - hostname-based Java routing
  - public-port-based Bedrock routing
  - keeper `/ws/data` channel
  - cross-relay forwarding support for TCP and UDP
- Progenitor
  - Wails desktop admin app for Windows
  - keeper health, live logs, bounded console input, task history, backup and schedule surfaces
  - backend bindings for workspaces, memberships, keeper assignment, and instance file management
- Eggs shipped in source
  - `minecraft-java-paper`
  - `minecraft-java-fabric`
  - `minecraft-bedrock`

Still pending operator signoff:

- production-proven multi-machine acceptance coverage beyond the smoke scripts in `scripts/`
- full end-user Keeper workspace GUI parity on top of the new workspace-aware Central API

## Current v1 contract

Java Edition is the primary supported path for v1. Both Paper and Fabric are shipped Java paths and are meant to enter through a Relay using hostname-based routing. Bedrock is now routed through ATGS as a UDP relay path keyed by a Central-assigned public port, so Keepers do not need to expose their own public Bedrock port for the intended v1 flow.

The current architecture is intentionally locked to these roles:

- Central is the control plane and routing authority
- Relay is the Java data plane
- Keeper runs the actual server containers
- Progenitor is the operator client

Central is not supposed to sit in the Java player byte stream. Relay does that job.

## Ports to plan around

Required for a normal Java relay deployment:

- `8080/tcp`
  - Central admin API
  - typically keep this private or behind a reverse proxy
- `8443/tcp`
  - Central keeper / relay mTLS listener
- `25565/tcp`
  - Relay Java ingress
- `19132-19231/udp`
  - Relay Bedrock ingress range
  - Central assigns one public Bedrock UDP port per Bedrock instance from this range by default
- `7443/tcp`
  - Relay keeper data channel
- `7444/tcp`
  - Relay peer mesh, only needed when running more than one relay

Keeper direct-connect is no longer the primary Bedrock contract. Keepers still bind a local UDP host port, but Relay is meant to own the public ingress.

## Binaries expected in a release

- `dist/central-linux-amd64`
- `dist/keeper-linux-amd64`
- `dist/keeper-windows-amd64.exe`
- `dist/progenitor-windows-amd64.exe`

The release package should also include:

- `deploy/`
- `docs/`
- `eggs/`
- `migrations/`

## Operator validation checklist

Use `docs/v1-validation-checklist.md` before calling a build release-ready. At minimum:

1. Central boots against Postgres from a fresh setup.
2. Keeper `init` works with and without a trailing slash in the Central URL.
3. Progenitor connects and can list keepers and instances.
4. A Paper instance can be created and started on a Keeper.
5. A Paper instance can be created, started, and joined through the Relay hostname path.
6. A Fabric instance can be created, started, and joined through the Relay hostname path.
7. A Bedrock instance can be created, started, and reached through its assigned Relay UDP port.
8. Progenitor `Operations` shows logs, recent tasks, audit history, and bounded console input against a real Central.

## Useful scripts

- `scripts/e2e_phase3_local.ps1`
  - Windows-friendly Java relay smoke test
- `scripts/e2e_phase4.sh`
  - backup + restore end-to-end verification
- `scripts/package_release.ps1`
  - rebuilds binaries and assembles the release package from source

## Release note

This repo is in stabilization mode for a flagship v1 release with official Paper, Fabric, and Bedrock support. The current v1.1 work is a bridge release centered on operator leverage and migration prep: better observability, cleaner recovery flows, and a clearer release/signoff contract before the larger v1.2 multi-tenant rewrite.
