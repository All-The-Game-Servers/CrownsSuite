# Crowns Suite Downloads

This folder mirrors the current public artifacts for the suite so a GitHub repository can expose direct downloads without waiting on releases.

Only the latest supported build for each plugin is kept here to avoid downloading the wrong version.

## Plugin JARs

- `CrownsAPI-1.3.0.jar`
- `CrownsEconomy-1.3.0.jar`
- `CrownsAdmin-1.3.0.jar`
- `CrownsEvents-1.3.0.jar`
- `CrownsDrugs-1.3.0.jar`
- `CrownsMMO-1.7.0.jar`
- `CrownsTerrain-1.5.2.jar`

## Resource Pack

- `CrownsSuite-ResourcePack-1.5.0.zip`
- `CrownsSuite-ResourcePack-1.5.0.sha1`
- `CHECKSUMS.txt`

## Recommended Repo Publishing Layout

Keep this folder committed so players and staff can fetch the latest suite builds directly from the repository page, and optionally duplicate these files into GitHub Releases later.

Note: `CrownsMMO` and `CrownsTerrain` require the updated `CrownsAPI-1.3.0.jar` from this same folder, because the shared provider surfaces include MMO, terrain, suite status, and quest integration. The current resource pack is `1.5.0`, rebuilt for Minecraft `1.21.11`, so the MMO, terrain, quest, and suite status visuals resolve in-game. `CrownsMMO-1.7.0.jar` is the playable Floor 1 quest build. `CrownsTerrain-1.5.2.jar` is the reference-guided Floor 1 worldgen heightmap-placement build and defaults to the fresh world `crowns_floor_1_v2`.
