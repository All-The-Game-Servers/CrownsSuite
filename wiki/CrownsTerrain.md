# CrownsTerrain

CrownsTerrain is the Crowns Suite terrain identity plugin.

It is built primarily for CrownsMMO floor worlds: CrownsMMO owns progression, bosses, parties, loot, and unlocks, while CrownsTerrain owns how the floor worlds feel when players arrive.

## What It Adds

- WorldPainter-backed macro terrain for the current Floor 1 vertical slice
- Hybrid route-first floor engine generation for CrownsMMO worlds
- Floor-themed terrain palettes
- Custom code-authored villages
- Camps, road markers, waystones, and starter shrines
- Boss arena location metadata
- Landmark and waystone locations
- Suite GUI section and `/cterrain` commands

## Custom Villages

Villages are safe/useful by default.

- No default free loot chests
- NPC-ready buildings
- Clear arrival paths
- Notice-board and guide-space style layouts
- Floor-specific materials and silhouettes

The first version uses code templates instead of schematic files so the plugin can be rebuilt and versioned cleanly.

## 1.1.0 Living Floor 1

CrownsTerrain `1.1.0` focuses on making Floor 1 feel alive while keeping it survival-friendly.

- First Haven villages are larger and more varied.
- Villages can include modular houses, market stalls, wells, gardens, watchtowers, and notice-board spaces.
- Floor 1 gains small camps, road markers, waystones, and starter shrines.
- These structures are safe/useful by default and do not add free loot chests.

## 1.2.0 Procedural Floor Worlds

CrownsTerrain `1.2.0` moves floor layout toward seed-based procedural generation.

- Floor 1 defaults to a `16,000 x 16,000` livable starter world.
- Current WorldPainter test default Floor 1 world is `crowns_floor_1_wp_slice`, so existing survival `world` and older Crowns floor worlds are not overwritten.
- Floor 2+ default to `8,000 x 8,000` adventure worlds.
- Villages, camps, waystones, road markers, shrines, landmarks, and arenas can be selected from seeded safe candidates.
- Explicit config coordinates still win, and generated points persist in the shared database.
- Existing generated worlds are not overwritten or regenerated automatically.

## CrownsMMO Integration

CrownsMMO asks CrownsAPI whether a terrain provider is installed when it creates a floor world.

- If CrownsTerrain is installed, MMO floors can use CrownsTerrain generators.
- If CrownsTerrain is missing, CrownsMMO falls back to normal Paper world creation.
- Existing floor unlocks, boss clears, party credit, and resource drops remain CrownsMMO data.

## Commands

- `/cterrain info`
- `/cterrain preview <floor>`
- `/cterrain villages <floor>`
- `/cterrain verify floor <floor>`
- `/cterrain admin create <floor>`
- `/cterrain admin blueprint <floor>`
- `/cterrain admin debugmaps <floor>`
- `/cterrain admin generate <floor>`
- `/cterrain admin status <floor>`
- `/cterrain admin cancel <floor>`
- `/cterrain admin locate village <floor>`
- `/cterrain admin locate camp <floor>`
- `/cterrain admin locate waystone <floor>`
- `/cterrain admin locate road_marker <floor>`
- `/cterrain admin locate shrine <floor>`
- `/cterrain admin locate arena <floor>`
- `/cterrain admin tp <type> <floor> [key]`
- `/cterrain admin list <floor>`
- `/cterrain admin reload`
- `/cterrain admin regenerate <floor>` explains the guarded manual reset process and does not delete worlds.
- `/cterrain floor status <floor>`
- `/cterrain floor repair <floor>`
- `/cterrain floor anchors <floor>`
- `/cterrain floor pregenerate <floor> critical`
- `/cterrain floor qa <floor>`

## First Release Direction

CrownsTerrain `1.2.0` focuses on procedural floor identity. Floor 1 remains survival-friendly and livable by default, while higher floors become tighter adventure spaces with fantasy MMO-inspired terrain.

## 1.3.0 Quest Integration

CrownsTerrain `1.3.0` keeps generation ownership separate from MMO progression, but exposes richer point metadata through CrownsAPI.

- CrownsMMO quests can reference generated villages, camps, landmarks, waystones, road markers, shrines, and arenas.
- Persisted terrain points remain stable across restart, so quest locations do not reshuffle.
- If CrownsTerrain is not installed, CrownsMMO continues to run and simply skips terrain-location quest objectives.
- `/cterrain verify floor 1` checks that the Floor 1 world is loaded and that First Haven contains generated structure blocks.

## 1.4.0 Floor 1 Terrain Relaunch

CrownsTerrain `1.4.0` replaces the early prototype Floor 1 with a fresh-world relaunch.

- Floor 1 defaults to `crowns_floor_1_v2` and the `first_haven_relaunch` profile.
- First Haven is generated as a physical starter town with a spawn plaza, roads, houses, farms, market space, notice board, shrine, waystone, watchtower, and nearby camp.
- Terrain uses named regions: meadow basin, oak highlands, river valley, starter forest, farmland flats, shrine ridge, and gate wilds.
- The First Gate arena is now a visible generated structure with arena floor, gate arch, ruins, and approach routes.
- Trees, rocks, ponds, fallen logs, and groves replace the old diagonal sapling pattern.
- Structure blueprints are bundled inside the plugin and placed by CrownsTerrain without requiring WorldEdit.
- `/cterrain verify floor 1` now checks for real First Haven, camp, waystone, and arena blocks.

This release does not fix MMO death/respawn routing. Until the dedicated MMO integration patch, use `/cmmo start` or `/cmmo floor 1` to return to the floor world if a player respawns elsewhere.

## 1.5.0 Reference-Guided Worldgen

CrownsTerrain `1.5.0` uses the reference pack as design inspiration only. No third-party image or schematic asset is bundled.

- First Haven now uses anchored settlement planning: civic core, market road, farming terraces, residential rise, defensive edge, road edge, and wilderness edge.
- Preview output reports regions, districts, tree pools, and hydrology so staff can sanity-check the generated identity before playtesting.
- Floor 1 generation adds a clearer pass stack: macro terrain, hydrology, biome paint, roads/supports, structures, and clutter.
- Roads add slope support details such as retaining walls, stair accents, fence edges, lamps, bridges, and switchback templates.
- Forests use role-based pools: canopy trees, edge trees, understory, mushrooms, roots/stumps, fallen logs, and rare hero trees.
- New original templates cover gatehouse, bridge, town hall, mill, terraced farm, hillside house, switchback stairs, retaining wall, large shrine, giant tree base, stream camp, and ruined gate marker.
- `/cterrain verify floor 1` now checks town anchors, gatehouse approach blocks, hero-tree/vertical landmark blocks, camps, waystones, and arena structures.

## 1.5.1 Terrain Stability Hotfix

CrownsTerrain `1.5.1` fixes the first serious test issues from the `1.5.0` relaunch.

- Blueprint footprints now flatten and clear the generated terrain under them, so houses, town structures, and arena pieces should not spawn half-buried in hills.
- Foundation supports extend deeper under structures to hide small slope mismatches.
- Floor 1 height noise is gentler, reducing the repeated rolling-hill look.
- Stream and pond placement is more conservative so water appears in low/valley terrain instead of carving random cut-out trenches through hills.
- Tree clusters now use taller trunks, wider canopies, roots, and mixed leaves instead of tiny sapling-like blobs.

## Previous Iris-Informed Placement Pass

The earlier Iris-informed pass used Iris as architectural reference material without copying Iris code.

- Structure planning now follows a heightmap-aware placement approach: each blueprint samples the terrain under its own footprint and fits its base Y near the median local ground height.
- The existing platform clear/foundation pass remains as a safety net, but structures no longer rely on one town-wide Y value for every building.
- This should reduce buried buildings, floating foundations, and huge artificial shelves around First Haven pieces.
- The larger Iris lesson is that CrownsTerrain needs a real staged engine over time: resource loaders, object placement, biome/region actuators, decoration passes, metrics, and pregeneration tools instead of one oversized generator class.

## 1.5.3 Floor 1 v3 Fullscale Relaunch

CrownsTerrain `1.5.3` makes Floor 1 a fresh high-fantasy MMO starter world at `crowns_floor_1_v3`.

- Floor 1 uses the `first_haven_v3` profile and requires a fresh world to see the relaunch.
- First Haven expands into a large authored town with civic, residential, market, farming, defensive, and wilderness-edge pieces.
- New original templates include fountain plaza, tall homes, garden homes, blacksmith, barn, market row, gate towers, wall fragments, cliff overlook, river bridge, arena threshold, and staging area.
- Terrain generation emphasizes a sheltered valley, shrine ridges, gate wilds, smoother river paths, authored road routes, and stronger high-fantasy silhouettes.
- `/cterrain admin tp <type> <floor> [key]` lets staff teleport directly to generated points without copying coordinates.
- `/cterrain verify floor 1` is now a stricter QA gate for physical structure count, route blocks, hydrology, region variety, First Haven, and First Gate arena.

## 1.5.4 Set-Map Floors + Admin Pregeneration

CrownsTerrain `1.5.4` pivots Floor 1 to a code-authored set map at `crowns_floor_1_v4`.

- `/cterrain admin generate 1` creates/loads the floor and pregenerates the critical route before players use it.
- `/cterrain admin status 1` reports `not-generated`, `generating`, `critical-ready`, `complete`, `failed`, or `cancelled`.
- Normal player teleports into unready set-map floors are blocked until the floor is `critical-ready`.
- The generated route includes First Haven, market square, farm gate, starter camp, starter shrine, first waystone, north road, arena approach, and First Gate arena.
- `/cterrain verify floor 1` now checks generation status plus physical blocks for the set-map town, farms, roads, shrine, waystone, camp, and arena.
- This release keeps old worlds untouched; staff should test using the fresh `crowns_floor_1_v4` world.

## 1.6.0 Hybrid Blueprint Generator

CrownsTerrain `1.6.0` moves Floor 1 to the fresh hybrid route-first world `crowns_floor_1_v5`.

- `/cterrain admin blueprint 1` precomputes immutable floor intent from the seed before chunks render.
- `/cterrain admin debugmaps 1` writes height, slope, moisture, biome, roads, parcels, landmarks, and QA PNGs under the plugin data folder.
- `/cterrain admin generate 1` ensures the blueprint exists, creates/loads `crowns_floor_1_v5`, and pregenerates the critical route before players arrive.
- The chunk generator reads only immutable blueprint data and local coordinates. It does not do world/chunk lookups during generation.
- The authored spine remains First Haven, market square, farm gate, starter camp, starter shrine, first waystone, arena approach, and First Gate arena.
- Procedural support now belongs around that spine: macro terrain, moisture/biome variation, river masks, road corridors, parcels, and jittered wilderness decoration candidates.
- `/cterrain verify floor 1` checks both blueprint QA metrics and physical world evidence before staff treat the floor as release-ready.

## 1.7.0 Full Hybrid Floor Engine

CrownsTerrain `1.7.0` moves Floor 1 to the fresh hybrid-engine world `crowns_floor_1_v6`.

- `/cterrain admin blueprint 1` writes a versioned blueprint artifact set under the plugin data folder.
- The artifact set includes `floor.bpbin`, `floor.index.json`, `scores.json`, and debug PNGs for height, slope, moisture, biome, hydrology, roads, parcels, landmarks, and QA.
- Runtime chunk generation reads immutable blueprint data and pure local coordinates only.
- A blueprint-backed biome provider paints Floor 1 biomes without relying on ad-hoc chunk decisions.
- A LimitedRegion populator handles local trees, rocks, and ruins so object placement is separated from macro terrain.
- `/cterrain admin generate 1` pregenerates the critical route before players arrive.
- Normal player teleports remain blocked until Floor 1 reaches `CRITICAL_READY` or better.
- `TerrainProvider` remains compatible for CrownsMMO quests, boss arenas, waystones, shrines, camps, road markers, landmarks, and villages.

## 1.8.0 Floor Runtime Platform

CrownsTerrain `1.8.0` becomes the runtime owner for managed MMO floor readiness.

- Terrain exposes Floor 1 state through CrownsAPI's `FloorRuntimeProvider`.
- Runtime snapshots include readiness state, safe-ready status, QA lines, repair steps, and known anchors.
- New floor commands wrap the most important setup and diagnosis flow:
  - `/cterrain floor status 1`
  - `/cterrain floor repair 1`
  - `/cterrain floor anchors 1`
  - `/cterrain floor pregenerate 1 critical`
  - `/cterrain floor qa 1`
- CrownsMMO uses runtime anchors for `/cmmo start`, floor teleports, respawn routing, and reconnect recovery.
- Terrain health now warns the suite when Floor 1 is not safe for players, so admins can catch broken setup before launch.

The goal of `1.8.0` is not to expand every part of Floor 1. It locks down the vertical slice: First Haven, road route, starter camp, shrine, waystone, farm route, arena approach, and First Gate arena must be ready before normal MMO entry feels safe.

## 1.8.1 Structure Converter Pipeline

CrownsTerrain `1.8.1` adds the first usable bridge between external block-art tools and the plugin's floor renderer.

- Custom `.ctpl` templates can be dropped into `plugins/CrownsTerrain/structures`.
- Custom templates load alongside bundled templates and can override bundled keys for rapid replacement.
- `/cterrain structure folder` shows the exact server folder to copy converter output into.
- `/cterrain structure reload` reloads config, points, blueprints, bundled templates, and custom templates.
- `/cterrain structure list` and `/cterrain structure info <key>` help confirm that a converted structure loaded correctly.
- Repo tools under `tools/terrain` convert MagicaVoxel `.vox` and Blender-style block JSON into `.ctpl`.

This is the start of the authored-map workflow: build structures with voxel/block tools, convert them, load them into CrownsTerrain, then use them as high-quality pieces for First Haven, roads, camps, shrines, arenas, and future floor zones.

## 1.8.2 Structure Studio

CrownsTerrain `1.8.2` adds in-game structure authoring tools on top of the `.ctpl` pipeline.

- `/cterrain studio wand` gives admins a selection wand.
- `/cterrain studio pos1` and `/cterrain studio pos2` set capture corners from the player's current block.
- `/cterrain studio capture <key>` saves non-air blocks as `plugins/CrownsTerrain/structures/<key>.ctpl`.
- `/cterrain studio preview <key> [rotation] [seconds]` shows player-only ghost blocks and clears them automatically.
- `/cterrain studio place <key> [rotation]` stages real placement, and `/cterrain studio confirm` writes blocks.

## 1.8.3 Blender Floor 1 Kit

CrownsTerrain `1.8.3` upgrades the offline structure pipeline into a repeatable Blender-authored Floor 1 kit.

- `tools/terrain/build_floor1_kit.ps1` generates an editable Blender scene on the Seagate tools drive, exports each top-level collection as JSON, converts it to `.ctpl`, validates the kit, and bundles the templates into CrownsTerrain.
- The first kit includes larger First Haven pieces: town hall, plaza, homes, market street, market hall, blacksmith, barn, farms, shrine, waystone, camp, bridge, route stamps, arena approach, and First Gate platform.
- Blender is only an authoring tool. Servers still run from `.ctpl` templates, so there is no runtime Blender dependency.
- Floor 1 planning now prefers the new `fh_*` templates while older prototype templates remain compatible for custom overrides and fallback testing.

## 1.8.4 Offline Visual QA

CrownsTerrain `1.8.4` makes Floor 1 reviewable without a live Minecraft server.

- `tools/terrain/render_floor1_previews.ps1` renders top, front, side, and isometric PNGs for every `fh_*` template.
- The contact sheet at `build/terrain-preview/floor1-kit-contact-sheet.png` lets staff review the full kit quickly.
- The layout map at `build/terrain-preview/floor1-layout.png` shows First Haven, route pieces, camp, shrine, and arena footprints.
- `build/terrain-preview/floor1-layout-report.json` flags likely overlaps and spacing issues before in-game testing.
- `.ctpl` palettes now support Bukkit BlockData strings, so captured stairs, slabs, fences, trapdoors, panes, and lantern states are preserved where Paper exposes them.

## 1.8.5 WorldPainter Macro Terrain Pipeline

CrownsTerrain `1.8.5` adds WorldPainter as the macro-terrain authoring layer while keeping Blender and `.ctpl` templates as the structure layer.

- Floor 1 defaults to `crowns_floor_1_wp_slice` for the first `2k x 2k` vertical slice.
- `tools\terrain\worldpainter\build_floor1_masks.ps1` generates heightmap, biome, river, road, settlement, landmark, composite preview, and JSON report artifacts.
- `tools\terrain\worldpainter\verify_worldpainter_install.ps1` checks for `wpscript` under `D:\CrownsSuiteTools\Apps\WorldPainter` or `WORLDPAINTER_HOME`.
- `tools\terrain\worldpainter\build_floor1_worldpainter.ps1` builds the `.world` project once WorldPainter is installed.
- `tools\terrain\worldpainter\export_floor1_world.ps1` exports the Minecraft world folder for server testing.
- `/cterrain admin installsource 1` copies the cached export into the server root without starting pregeneration.
- CrownsTerrain source mode `worldpainter-plus-ctpl` refuses to create a replacement world when the exported map folder is missing.
- `/cterrain admin generate 1` auto-installs the cached export if needed, then pregenerates and overlays CrownsTerrain route blocks plus authored `.ctpl` structures onto the exported WorldPainter base map.

Recommended workflow:

1. Run `build_floor1_masks.ps1`.
2. Inspect `floor1-composite-preview.png`.
3. Build/export the WorldPainter project.
4. Keep `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2\crowns_floor_1_wp_slice` available on the server machine as the install cache.
5. Run `/cterrain admin generate 1`.
6. Run `/cterrain verify floor 1`.
