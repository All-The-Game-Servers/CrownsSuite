# CrownsTerrain

CrownsTerrain is the Crowns Suite terrain identity plugin.

It is built primarily for CrownsMMO floor worlds: CrownsMMO owns progression, bosses, parties, loot, and unlocks, while CrownsTerrain owns how the floor worlds feel when players arrive.

## What It Adds

- Hybrid floor generation for CrownsMMO worlds
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
- Relaunch default Floor 1 world is `crowns_floor_1_v3`, so existing survival `world` and older Crowns floor worlds are not overwritten.
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
