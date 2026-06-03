# Crowns Suite

Crowns Suite is being relaunched as a smaller, cleaner plugin platform for the Lowlight SMP.

The active `master` branch now starts from the new combat foundation:

- `CrownsAPI 0.1.4` - shared platform, suite GUI shell, module registration, required resource-pack delivery, hardened gesture input, cooldowns, resource meters, ability lifecycle events, telemetry, targeting helpers, particle helpers, and ability-family metadata.
- `CrownsMagic 0.3.0` - gesture-cast magic using mana, Arcane Rank, school mastery, practice objectives, spell bindings, spellbook GUI, cooldowns, and particle-forward spell effects.
- `CrownsSwords 0.3.1` - gesture-based sword arts using stamina, Blade Rank, style mastery, practice objectives, skill bindings, skillbook GUI, cooldowns, non-destructive action combat, and the admin-only Excalibur test sword.

The previous Crowns Suite work is preserved on the `deprecated/pre-relaunch-suite` branch. That branch contains the old economy/admin/events/drugs/MMO/terrain experiments, resource-pack iterations, and historical docs.

## Downloads

Current artifacts live in `downloads`:

- `CrownsAPI-0.1.4.jar`
- `CrownsMagic-0.3.0.jar`
- `CrownsSwords-0.3.1.jar`
- `CrownsSuite-ResourcePack-1.8.1.zip`
- `CrownsSuite-ResourcePack-1.8.1.sha1`

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

Current pack: `CrownsSuite-ResourcePack-1.8.1.zip`

SHA1: `f22e011db601359a46bb0a006b655e4dcafb771e`

The pack keeps stable `lowlight/...` model paths for Magic, Swords, and future Crowns plugins. It is intended to be hosted from GitHub Releases for CrownsAPI resource-pack delivery.

## Building

This repo is a lightweight Java 25 multi-module project targeting Paper/Minecraft `26.1.2`:

```powershell
gradle packageDownloads
```

Paper `26.1.2` requires Java 25. Install a JDK 25 toolchain before compiling or packaging current builds.

## What Comes Next

The relaunch roadmap is intentionally narrower now:

- Harden `CrownsAPI` as the shared platform.
- Expand `CrownsMagic` with deeper Elemental, Restoration, and Astral spell schools.
- Expand `CrownsSwords` with deeper Flash, Guard, and Phantom weapon-art styles.
- Add future plugins for custom entities, bosses, and eventually terrain/world engines.
- Keep the existing terrain converter pipeline as tooling, not an active plugin, until the new platform is ready for it.
