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
- `/cterrain admin locate village <floor>`
- `/cterrain admin locate camp <floor>`
- `/cterrain admin locate waystone <floor>`
- `/cterrain admin locate road_marker <floor>`
- `/cterrain admin locate shrine <floor>`
- `/cterrain admin locate arena <floor>`
- `/cterrain admin list <floor>`
- `/cterrain admin reload`

## First Release Direction

CrownsTerrain `1.2.0` focuses on procedural floor identity. Floor 1 remains survival-friendly and livable by default, while higher floors become tighter adventure spaces with fantasy MMO-inspired terrain.

## 1.3.0 Quest Integration

CrownsTerrain `1.3.0` keeps generation ownership separate from MMO progression, but exposes richer point metadata through CrownsAPI.

- CrownsMMO quests can reference generated villages, camps, landmarks, waystones, road markers, shrines, and arenas.
- Persisted terrain points remain stable across restart, so quest locations do not reshuffle.
- If CrownsTerrain is not installed, CrownsMMO continues to run and simply skips terrain-location quest objectives.
