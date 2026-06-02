# ATGS Operations Guide

This is the day-2 operator guide for the current ATGS v1 source tree.

## What v1 supports right now

- Java servers through the Relay
- Fabric-based Java servers through the same Relay path
- Bedrock servers through the Relay UDP path
- Central-managed backups
- Keeper enrollment and revocation
- Progenitor desktop administration

Java relay is still the primary release path, but this source tree now also carries the Bedrock UDP relay path. Bedrock needs real operator validation before you should treat it as production-proven.

## First deployment checklist

1. Start Postgres.
2. Run `central setup`.
3. Start Central with `central serve`.
4. Provision at least one relay with `central mint-relay-cert`.
5. Start Relay.
6. Run `keeper init` on a host machine.
7. Start Keeper.
8. Connect Progenitor.
9. Create a Paper or Fabric instance.
10. Validate a Java join through the Relay hostname.
11. Validate a Bedrock connect through the assigned Relay UDP port.

Use `v1-validation-checklist.md` as the release gate.

## Creating your first Java server

After Central, Relay, and Keeper are up:

1. Open Progenitor.
2. Go to `Instances`.
3. Create a new instance on a connected Keeper.
4. Choose `minecraft-java-paper` or `minecraft-java-fabric`.
5. Assign a hostname that will be routed through the Relay.
6. Start the instance.
7. Verify the create and start tasks return a non-zero `host_port`.
8. Join through the Relay ingress using the hostname you assigned.

For the current source tree, Paper is still the simplest Java happy path, but Fabric is now a first-class shipped option using the same relay contract.

## Fabric operations

Fabric instances use the shipped `minecraft-java-fabric` egg and the same Java relay path as Paper.

- Put server mods into the instance data directory on the Keeper host.
- Keep the client modpack aligned with the server's Fabric loader and mod set.
- Use the same hostname-based ingress validation you use for Paper.

## Bedrock routing

Bedrock is no longer meant to be a direct-connect deployment in the intended v1 path.

- Central assigns each Bedrock instance a public UDP port
- Relay binds that public port and forwards packets to the owning Keeper
- Keepers only need local Docker UDP binding, not public router exposure, for the flagship path
- Cross-relay Bedrock depends on the peer mesh the same way Java cross-relay depends on it

Recommended defaults:

- `ATGS_CENTRAL_BEDROCK_PUBLIC_PORT_MIN=19132`
- `ATGS_CENTRAL_BEDROCK_PUBLIC_PORT_MAX=19231`
- `ATGS_RELAY_BEDROCK_BIND_HOST=0.0.0.0`
- `ATGS_RELAY_BEDROCK_IDLE=90s`

## Keeper enrollment notes

Keeper enrollment should work whether the operator enters:

- `https://central.example.com:8443`
- `https://central.example.com:8443/`

The setup wizard trims trailing slashes and the enroll client trims them defensively for older `keeper.env` files.

## Ports to open

Central:

- `8443/tcp` for keeper, relay, and progenitor mTLS traffic
- `8080/tcp` for the admin API if you are not putting it behind a reverse proxy

Relay:

- `25565/tcp` for Java ingress
- `7443/tcp` for keeper data-channel connections
- `7444/tcp` for relay peering when more than one relay is deployed

- `19132-19231/udp`
  - Relay Bedrock ingress range

Keeper local runtime only:

- keeper-local UDP host ports are dynamically assigned and do not need to be publicly exposed for the relay path
## Common workflows

### Mint a keeper enrollment token

```bash
central mint-enrollment-token
```

### Mint a relay bundle

```bash
central mint-relay-cert /path/to/relay-state
```

Copy that directory to the relay host and use it as `ATGS_RELAY_STATE_DIR`.

### Start a relay

Set at minimum:

- `ATGS_RELAY_STATE_DIR`
- `ATGS_RELAY_CENTRAL_SYNC_URL`
- `ATGS_RELAY_INGRESS_ADDR`
- `ATGS_RELAY_BEDROCK_BIND_HOST`
- `ATGS_RELAY_DATA_ADDR`
- `ATGS_RELAY_PEER_ADDR`

Then run:

```bash
relay serve
```

### Revoke a keeper

Use Progenitor or call the Central API. Revocation does two things:

1. queues a `keeper.revoke` task to wipe local keeper identity if it is online
2. adds the cert serial to the CRL so reconnect attempts are rejected

## Production blockers to verify before release

- Progenitor can connect to a real Central, not just build locally
- Keeper can start cleanly on Linux and Windows from the generated `keeper.env`
- Central `setup` behaves correctly when run without root and when systemd install is requested
- Java relay smoke passes from instance create through player byte forwarding

## Current limitations

- No production-proven automated multi-machine acceptance harness in-tree beyond the smoke scripts
- Cross-relay forwarding exists for both Java and Bedrock in code but should still be operator-validated before broad rollout
