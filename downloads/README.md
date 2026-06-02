# Crowns Suite Downloads

This folder mirrors the current public artifacts for the Crowns relaunch so a GitHub repository can expose direct downloads without waiting on releases.

The active relaunch build is `CrownsAPI 0.1.2`, `CrownsMagic 0.1.0`, and `CrownsSwords 0.1.0`. Older suite jars may still appear locally if OneDrive has locked them, but they are not part of the current relaunch line.

## Plugin JARs

- `CrownsAPI-0.1.2.jar`
- `CrownsMagic-0.1.0.jar`
- `CrownsSwords-0.1.0.jar`

## Resource Pack

- `CrownsSuite-ResourcePack-1.7.0.zip`
- `CrownsSuite-ResourcePack-1.7.0.sha1`
- `CHECKSUMS.txt`

## Recommended Repo Publishing Layout

Keep this folder committed so players and staff can fetch the latest suite builds directly from the repository page. Resource-pack zips should also be published into GitHub Releases so CrownsAPI can resolve the latest required pack with `/capi pack refresh`.

Note: `CrownsMagic-0.1.0.jar` and `CrownsSwords-0.1.0.jar` require `CrownsAPI-0.1.2.jar`. Magic uses gesture casting, spellbook loadouts, mana, cooldowns, and shared API particle helpers. Swords uses the same input service for sword arts, stamina, cooldowns, and non-destructive action combat.
