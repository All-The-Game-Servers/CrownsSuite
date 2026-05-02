# Installation

## Plugin Order

1. CrownsAPI
2. CrownsEconomy
3. CrownsAdmin
4. CrownsEvents
5. CrownsDrugs
6. CrownsMMO
7. CrownsTerrain

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
- Current pack SHA1: `38ea1def8cf48afa624711276f39f7d023f1134b`
- The default resource-pack config keeps ValhallaMMO-safe manual delivery enabled and does not force client prompts
