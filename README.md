# Crowns Suite

Crowns Suite is the modular Lowlight SMP plugin suite by XKStudios.

`1.4.0` is the suite-wide `BetterPvP-Informed Platform Cohesion` release. CrownsEvents is on `1.5.0` with the Season 1 vanilla Endfall Opening Week, CrownsMMO is on `1.8.0`, and CrownsTerrain is on `1.8.2` with the first in-game Structure Studio pipeline.

It currently includes:

- `CrownsAPI` for shared data, GUI shell, and provider wiring
- `CrownsEconomy` for wallets, auctions, stalls, jobs, demand, trader, and gambling
- `CrownsAdmin` for moderation, vanish, staff tools, analytics, and inspection
- `CrownsEvents` for Nether Week, Endfall Week, and future seasonal content
- `CrownsDrugs` for grow, process, use, and sell contraband items through the Crowns economy
- `CrownsMMO` for Season 2 MMO progression, professions, combat identity, and world progression
- `CrownsTerrain` for hybrid MMO floor terrain, villages, landmarks, and arena placement

## Downloads

The current downloadable builds are stored in [downloads](C:\Users\sirgi\OneDrive\Documents\New project\downloads):

Only the latest supported build for each plugin/resource pack is kept in that folder, so the download list is intentionally short.

- `CrownsAPI-1.4.0.jar`
- `CrownsEconomy-1.4.0.jar`
- `CrownsAdmin-1.4.0.jar`
- `CrownsEvents-1.5.0.jar`
- `CrownsDrugs-1.4.0.jar`
- `CrownsMMO-1.8.0.jar`
- `CrownsTerrain-1.8.2.jar`
- `CrownsSuite-ResourcePack-1.5.0.zip`
- `CrownsSuite-ResourcePack-1.5.0.sha1`

## Wiki

The current wiki home page is in [wiki/Home.md](C:\Users\sirgi\OneDrive\Documents\New project\wiki\Home.md).

Suggested GitHub wiki structure:

- `Home`
- `Suite Architecture`
- `CrownsEconomy`
- `CrownsAdmin`
- `CrownsEvents`
- `CrownsDrugs`
- `CrownsMMO`
- `CrownsTerrain`
- `Installation`

## Resource Pack

The Crowns Suite resource pack source is stored in [resource-pack](C:\Users\sirgi\OneDrive\Documents\New project\resource-pack), and the distributable build is mirrored in [downloads](C:\Users\sirgi\OneDrive\Documents\New project\downloads).

Current resource-pack build: `CrownsSuite-ResourcePack-1.5.0.zip` with SHA1 `aa3a31780d939471971b0fd63dd3ed216f80e813`. It is rebuilt for Minecraft `1.21.11` resource-pack format `75`. CrownsAPI is configured so `/capi pack download` downloads this pack onto the server while keeping ValhallaMMO-safe manual delivery.

## Project Layout

- [api](C:\Users\sirgi\OneDrive\Documents\New project\api)
- [economy](C:\Users\sirgi\OneDrive\Documents\New project\economy)
- [admin](C:\Users\sirgi\OneDrive\Documents\New project\admin)
- [events](C:\Users\sirgi\OneDrive\Documents\New project\events)
- [drugs](C:\Users\sirgi\OneDrive\Documents\New project\drugs)
- [mmo](C:\Users\sirgi\OneDrive\Documents\New project\mmo)
- [terrain](C:\Users\sirgi\OneDrive\Documents\New project\terrain)
- [wiki](C:\Users\sirgi\OneDrive\Documents\New project\wiki)
- [resource-pack](C:\Users\sirgi\OneDrive\Documents\New project\resource-pack)

## 1.4.0 Highlights

- `CrownsAPI`: typed module registry, health states, `/capi modules`, `/capi downloads`, and richer status GUI diagnostics.
- `CrownsEconomy`: publishes market activity summaries for MMO/admin integrations and suite status cards.
- `CrownsAdmin`: adds `/ca suite` so staff can open the operations/module-health page directly.
- `CrownsEvents`: exposes suite activity summaries so live events can react to MMO, economy, and future ceremony hooks.
- `CrownsMMO`: adds `/cmmo status` and blocks normal entry into unready managed terrain floors with clear admin fix steps.
- `CrownsTerrain`: reports Floor 1 readiness through the shared TerrainProvider so MMO and Admin surfaces can diagnose generation state.
- `CrownsTerrain + CrownsMMO`: adds the shared FloorRuntimeProvider contract, Terrain-owned runtime anchors/QA/repair steps, MMO-safe floor entry, respawn routing, and reconnect routing.
- `downloads`: the verification script packages only current jars and refreshes checksums.

## CrownsEvents 1.5.0

- Ships Season 1 `Endfall Opening Week` as a vanilla End event with no CrownsMMO or CrownsTerrain dependency.
- Keeps safe install defaults: Nether Week remains the active event until staff run `/events admin activate end-opening`.
- Adds `/events admin activate <event>`, `/events admin schedule <event> <yyyy-MM-dd HH:mm>`, `/events admin start <event>`, `/events admin end <event>`, and `/events admin dryrun <event>`.
- Adds explicit Endfall config for guide text, relics, rewards, milestones, source drops, elite pressure, and End dimension-lock control.
- Keeps Nether Week and Endfall archive pages viewable without mixing progress, rewards, logs, or cache claims.

## 1.3.0 Highlights

- `CrownsAPI`: suite status page, provider health checks, module versions, resource-pack status, and cleaner shared navigation.
- `CrownsEconomy`: `/ce status` routes to the suite status surface while preserving the rebuilt economy loops.
- `CrownsAdmin`: operations dashboard remains the staff entry point and benefits from suite status visibility.
- `CrownsEvents`: archive/live event pages remain modular and ready for MMO quest/live-event hooks.
- `CrownsDrugs`: remains Crowns-backed and suite-navigation compatible.
- `CrownsMMO`: quest foundations connect floors, terrain POIs, kills, turn-ins, XP, and Crowns rewards.

## CrownsMMO 1.3.0 Preview

- CrownsMMO now supports Floor worlds, personal floor unlocks, Floor Boss gates, 16k Floor 1 borders, and higher-floor mob scaling.

## CrownsMMO 1.4.0 Preview

- CrownsMMO now adds Floor 1-3 resource drops, personal boss loot, tagged MMO items, utility-first adventurer gear, and vanilla-station recipe integration.

## CrownsMMO 1.5.0 Preview

- CrownsMMO now adds temporary parties for Floor Boss credit and persistent guild identity/roster foundations.

## CrownsMMO 1.6.0

- Adds `/cmmo quests`, `/cmmo quests active`, and `/cmmo quest <id>`.
- Adds additive quest progress and discovery tables.
- Supports exploration, kills, Floor Boss clears, gathering, and item turn-ins.
- Integrates CrownsTerrain points when installed, but safely runs without CrownsTerrain.

## CrownsMMO 1.7.0

- Adds the playable Floor 1 `First Haven Path` questline.
- Adds `/cmmo start` and relaunch onboarding that sends new adventurers to First Haven in the dedicated Floor 1 world. With CrownsTerrain `1.7.0`, Floor 1 defaults to `crowns_floor_1_v6` after staff run `/cterrain admin blueprint 1`, `/cterrain admin debugmaps 1`, and `/cterrain admin generate 1`.
- Adds quest board pages for available, active, completed, Floor 1, and story-path quests.
- Adds `/cmmo quests completed`, `/cmmo quests floor <number>`, and `/cmmo admin quest inspect <player>`.
- Adds clearer quest detail guidance, optional-provider status, reward previews, and progress messages.

## CrownsTerrain 1.7.0

- Moves Floor 1 to a fresh hybrid-engine world, `crowns_floor_1_v6`, so old floor worlds remain untouched.
- Adds a versioned blueprint artifact set: `floor.bpbin`, `floor.index.json`, `scores.json`, and debug maps.
- Adds a blueprint-backed biome provider and LimitedRegion decoration populator so runtime chunk generation stays thread-safe.
- Keeps `/cterrain admin generate 1`, `/cterrain admin status 1`, and `/cterrain admin cancel 1` for staff-controlled critical-route pregeneration.
- Builds the playable route first: First Haven, market square, farm gate, starter camp, starter shrine, waystone, roads, arena approach, and First Gate arena.
- Keeps `/cterrain admin tp <type> <floor> [key]` so staff can teleport directly to villages, camps, shrines, waystones, landmarks, road markers, and arenas.
- Blocks normal player teleports into unready managed floors until generation reaches `CRITICAL_READY`.
- CrownsMMO falls back to normal world creation and non-terrain quest behavior when CrownsTerrain is not installed.

## CrownsTerrain + CrownsMMO 1.8.0

- Adds a shared Floor Runtime Platform so Terrain owns floor state, anchors, QA lines, repair steps, and safe readiness.
- Adds `/cterrain floor status 1`, `/cterrain floor repair 1`, `/cterrain floor anchors 1`, `/cterrain floor pregenerate 1 critical`, and `/cterrain floor qa 1`.
- Updates `/cmmo status` to show Floor Runtime state and repair steps when Floor 1 is blocked.
- Routes `/cmmo start`, `/cmmo floor 1`, respawns, and reconnects through safe Floor Runtime anchors when CrownsTerrain is installed and ready.

## CrownsTerrain 1.8.1

- Adds the first custom structure pipeline for authored floor-map pieces.
- Loads custom `.ctpl` templates from `plugins/CrownsTerrain/structures` in addition to bundled templates.
- Adds `/cterrain structure list`, `/cterrain structure info <key>`, `/cterrain structure folder`, and `/cterrain structure reload`.
- Adds converter scripts under `tools/terrain` for MagicaVoxel `.vox`, Blender block JSON, and Blender cube-scene export.

## CrownsTerrain 1.8.2

- Adds `/cterrain studio wand`, region selection, capture, preview, place, confirm, and cancel.
- Captures non-air Creative builds into custom `.ctpl` files under `plugins/CrownsTerrain/structures`.
- Extends `.ctpl` palettes to support Bukkit BlockData strings while keeping old Material-only templates compatible.
- Preview uses player-only ghost blocks and real placement requires `/cterrain studio confirm`.

## Resource Pack 1.5.0

- Full Dark Arcane redraw for the suite branding pack.
- Adds CrownsMMO floor materials, boss trophies, adventurer gear, party/guild, quest, and MMO hub icons.
- Adds CrownsTerrain suite, village, arena, landmark, camp, road marker, waystone, shrine, and floor-theme icons.
- Keeps existing `lowlight/...` model paths stable for compatibility.

## Notes

- Suite-core plugin versions are currently aligned to `1.4.0` except CrownsEvents at `1.5.0`; CrownsMMO is `1.8.0` and CrownsTerrain is `1.8.2`.
- The suite is designed to preserve shared data through `CrownsAPI`.
- The repo is set up so the wiki content and downloadable jars can live alongside the source tree.
