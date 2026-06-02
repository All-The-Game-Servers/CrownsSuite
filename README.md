# Crowns Suite

Crowns Suite is being relaunched as a smaller, cleaner plugin platform for the Lowlight SMP.

The active `master` branch now starts from the new combat foundation:

- `CrownsAPI 0.1.2` - shared platform, suite GUI shell, module registration, resource-pack delivery, gesture input, cooldowns, resource meters, ability events, targeting helpers, and particle helpers.
- `CrownsMagic 0.1.0` - gesture-cast magic using mana, spell bindings, spellbook GUI, cooldowns, and particle-forward spell effects.
- `CrownsSwords 0.1.0` - gesture-based sword arts using stamina, skill bindings, skillbook GUI, cooldowns, and non-destructive action combat.

The previous Crowns Suite work is preserved on the `deprecated/pre-relaunch-suite` branch. That branch contains the old economy/admin/events/drugs/MMO/terrain experiments, resource-pack iterations, and historical docs.

## Downloads

Current artifacts live in `downloads`:

- `CrownsAPI-0.1.2.jar`
- `CrownsMagic-0.1.0.jar`
- `CrownsSwords-0.1.0.jar`
- `CrownsSuite-ResourcePack-1.7.0.zip`
- `CrownsSuite-ResourcePack-1.7.0.sha1`

Install `CrownsAPI` first. `CrownsMagic` and `CrownsSwords` both depend on it.

## Project Layout

- `api` - shared Crowns platform and action-combat API.
- `magic` - first relaunch gameplay plugin.
- `swords` - second relaunch gameplay plugin.
- `resource-pack` - current resource-pack source and generator.
- `tools` - preserved terrain/map-authoring converter pipeline for future terrain work.
- `wiki` - relaunch documentation pages.
- `downloads` - current public jar and resource-pack artifacts only.

## Resource Pack

Current pack: `CrownsSuite-ResourcePack-1.7.0.zip`

SHA1: `7829996977bbecc38204d3abe7e104af508e5e39`

The pack keeps stable `lowlight/...` model paths for Magic, Swords, and future Crowns plugins. It is intended to be hosted from GitHub Releases for CrownsAPI resource-pack delivery.

## Building

This repo is a lightweight Java 21 multi-module project:

```powershell
gradle packageDownloads
```

If Gradle is not installed locally, the modules can still be compile-checked with `javac` using the bundled Paper/Adventure API jars in the repo root.

## What Comes Next

The relaunch roadmap is intentionally narrower now:

- Harden `CrownsAPI` as the shared platform.
- Expand `CrownsMagic` with polished spell schools and better particles.
- Expand `CrownsSwords` with progression and weapon-art identity.
- Add future plugins for custom entities, bosses, and eventually terrain/world engines.
- Keep the existing terrain converter pipeline as tooling, not an active plugin, until the new platform is ready for it.
