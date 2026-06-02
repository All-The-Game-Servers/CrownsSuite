# ATGS Fabric Notes

ATGS now ships `minecraft-java-fabric` as an official Java egg.

## What it is

- Docker image: `itzg/minecraft-server:java21`
- Runtime type: `TYPE=FABRIC`
- Relay model: the same hostname-based Java relay path used by Paper

## Operator expectations

- Fabric is for modded Java servers.
- The server and every player joining it need compatible mod and loader versions.
- Relay behavior is unchanged from Paper. The only routing field that matters is the Java hostname you assign on create.

## Mod workflow

1. Create the Fabric instance from Progenitor.
2. Start it once so the standard server directory is created.
3. Stop it.
4. Copy Fabric mods into the instance data directory on the Keeper host.
5. Start it again.
6. Join through the hostname assigned in Progenitor with a matching client modpack.

## Validation target

Fabric is not a "manual hidden path" in this repo anymore. A v1 release should prove:

- instance create succeeds
- instance start reports a usable `host_port`
- Relay receives the hostname route
- a client can join through the Relay with the expected Fabric mods
