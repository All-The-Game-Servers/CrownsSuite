# Crowns Suite

Crowns Suite is the modular Lowlight SMP plugin suite by XKStudios.

`1.3.0` is the suite-wide `Integration Polish + Quest Foundations` release. CrownsMMO currently has its own `1.7.0` playable Floor 1 quest release.

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

- `CrownsAPI-1.3.0.jar`
- `CrownsEconomy-1.3.0.jar`
- `CrownsAdmin-1.3.0.jar`
- `CrownsEvents-1.3.0.jar`
- `CrownsDrugs-1.3.0.jar`
- `CrownsMMO-1.7.0.jar`
- `CrownsTerrain-1.3.0.jar`
- `CrownsSuite-ResourcePack-1.5.0.zip`
- `CrownsSuite-ResourcePack-1.5.0.sha1`

## Wiki

The current wiki home page is in [wiki/Home.md](C:\Users\sirgi\OneDrive\Documents\New project\wiki\Home.md).

Suggested GitHub wiki structure:

- `Home`
- `CrownsEconomy`
- `CrownsAdmin`
- `CrownsEvents`
- `CrownsDrugs`
- `CrownsMMO`
- `CrownsTerrain`
- `Installation`

## Resource Pack

The Crowns Suite resource pack source is stored in [resource-pack](C:\Users\sirgi\OneDrive\Documents\New project\resource-pack), and the distributable build is mirrored in [downloads](C:\Users\sirgi\OneDrive\Documents\New project\downloads).

Current resource-pack build: `CrownsSuite-ResourcePack-1.5.0.zip` with SHA1 `38ea1def8cf48afa624711276f39f7d023f1134b`. CrownsAPI is configured so `/capi pack download` downloads this pack onto the server while keeping ValhallaMMO-safe manual delivery.

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
- Adds quest board pages for available, active, completed, Floor 1, and story-path quests.
- Adds `/cmmo quests completed`, `/cmmo quests floor <number>`, and `/cmmo admin quest inspect <player>`.
- Adds clearer quest detail guidance, optional-provider status, reward previews, and progress messages.

## CrownsTerrain 1.3.0

- Adds a new terrain provider plugin for CrownsMMO floor worlds.
- Provides procedural floor terrain, seeded villages/camps/landmarks, Floor 1 at 16k, and Floor 2+ at 8k by default.
- Exposes generated POIs through CrownsAPI so CrownsMMO quests can reference villages, road markers, shrines, and landmarks.
- CrownsMMO falls back to normal world creation and non-terrain quest behavior when CrownsTerrain is not installed.

## Resource Pack 1.5.0

- Full Dark Arcane redraw for the suite branding pack.
- Adds CrownsMMO floor materials, boss trophies, adventurer gear, party/guild, quest, and MMO hub icons.
- Adds CrownsTerrain suite, village, arena, landmark, camp, road marker, waystone, shrine, and floor-theme icons.
- Keeps existing `lowlight/...` model paths stable for compatibility.

## Notes

- Suite-core plugin versions are currently aligned to `1.3.0`; CrownsMMO is `1.7.0` and CrownsTerrain is `1.3.0`.
- The suite is designed to preserve shared data through `CrownsAPI`.
- The repo is set up so the wiki content and downloadable jars can live alongside the source tree.
