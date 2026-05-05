# Installation

## Plugin Order

1. CrownsAPI
2. CrownsEconomy
3. CrownsAdmin
4. CrownsEvents
5. CrownsDrugs
6. CrownsTerrain
7. CrownsMMO

## Data

- Shared database lives under `plugins/CrownsAPI`
- Existing monolith data should be migrated into the shared suite database path
- Always back up your database before upgrading

## Resource Pack

- CrownsAPI provides manual resource-pack sharing and server-side pack download support
- Configure the pack URL, version, and SHA1 in the CrownsAPI config
- Run `/capi pack download` to download the configured resource pack onto the server
- Players can open the suite pack page in-game to receive the download link when manual delivery is used
- Current pack file: `CrownsSuite-ResourcePack-1.5.0.zip`
- Current pack SHA1: `aa3a31780d939471971b0fd63dd3ed216f80e813`
- Current target: Minecraft `1.21.11` resource-pack format `75`
- The default resource-pack config keeps ValhallaMMO-safe manual delivery enabled and does not force client prompts

## CrownsMMO / Terrain Relaunch Notes

- CrownsTerrain `1.7.0` defaults Floor 1 terrain generation to the fresh hybrid-engine world `crowns_floor_1_v6`, not the server's existing `world`, `crowns_floor_1`, `crowns_floor_1_v2`, `crowns_floor_1_v3`, `crowns_floor_1_v4`, or `crowns_floor_1_v5`.
- Run `/cterrain admin blueprint 1` first to precompute deterministic Floor 1 intent.
- Run `/cterrain admin debugmaps 1` to inspect the terrain QA maps before spending time in-game.
- Run `/cterrain admin generate 1` before player testing, then wait for `/cterrain admin status 1` to show `CRITICAL_READY`.
- Run `/cterrain verify floor 1` to confirm blueprint QA plus physical First Haven, road, farm, shrine, waystone, camp, and arena blocks.
- New adventurers can run `/cmmo start` to enter First Haven and begin the Floor 1 path.
- MMO respawn routing is not part of the terrain relaunch. If a death sends a player elsewhere, use `/cmmo start` or `/cmmo floor 1` until the follow-up MMO integration patch.
