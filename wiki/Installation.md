# Installation

## Plugin Order

1. CrownsAPI
2. CrownsEconomy
3. CrownsAdmin
4. CrownsEvents
5. CrownsDrugs

## Data

- Shared database lives under `plugins/CrownsAPI`
- Existing monolith data should be migrated into the shared suite database path
- Always back up your database before upgrading

## Resource Pack

- CrownsAPI provides manual resource-pack sharing in `1.2.0`
- Configure the pack URL, version, and SHA1 in the CrownsAPI config
- Players can open the suite pack page in-game to receive the download link
- Current pack file: `CrownsSuite-ResourcePack-1.2.0.zip`
- Current pack SHA1: `8939f46d7d8fcfacefe0bacfa47776a2f9aad645`
