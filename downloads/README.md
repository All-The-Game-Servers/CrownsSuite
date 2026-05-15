# Crowns Suite Downloads

This folder mirrors the current public artifacts for the suite so a GitHub repository can expose direct downloads without waiting on releases.

Only the latest supported build for each plugin is kept here to avoid downloading the wrong version.

## Plugin JARs

- `CrownsAPI-1.5.0.jar`
- `CrownsEconomy-1.4.0.jar`
- `CrownsAdmin-1.4.0.jar`
- `CrownsEvents-1.5.0.jar`
- `CrownsDrugs-1.4.0.jar`
- `CrownsMMO-1.8.0.jar`
- `CrownsTerrain-1.8.5.jar`

## Resource Pack

- `CrownsSuite-ResourcePack-1.6.0.zip`
- `CrownsSuite-ResourcePack-1.6.0.sha1`
- `CHECKSUMS.txt`

## Recommended Repo Publishing Layout

Keep this folder committed so players and staff can fetch the latest suite builds directly from the repository page. Resource-pack zips should also be published into GitHub Releases so CrownsAPI can resolve the latest required pack with `/capi pack refresh`.

Note: `CrownsEvents-1.5.0.jar` is the Season 1 vanilla Endfall Opening Week build and does not require CrownsMMO or CrownsTerrain. `CrownsMMO` and `CrownsTerrain` require the updated `CrownsAPI-1.5.0.jar` from this same folder, because the shared provider surfaces include module health, floor runtime readiness, suite activity, download diagnostics, and required resource-pack delivery. The current resource pack is `1.6.0`, rebuilt for Minecraft `1.21.11`, so the MMO, terrain, quest, and suite status visuals resolve in-game. `CrownsMMO-1.8.0.jar` and `CrownsTerrain-1.8.5.jar` are the current Floor Runtime Platform builds; Terrain `1.8.5` adds the WorldPainter macro-terrain pipeline for `crowns_floor_1_wp_slice` while keeping Blender/`.ctpl` structures as the overlay layer.
