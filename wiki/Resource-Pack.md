# Resource Pack

Crowns Suite ships with a Dark Arcane resource pack that gives the suite a shared visual identity.

## What It Covers

- Suite home and navigation icons
- Economy hub icons
- Admin hub icons
- Event relics and rewards
- Drugs item families
- MMO floor resources, boss trophies, party/guild icons, and adventurer gear
- Terrain hub, village, arena, landmark, camp, road marker, waystone, shrine, and floor-theme icons

## 1.5.0 Focus

- Full Dark Arcane redraw with sharper 32x silhouettes
- Complete CrownsMMO coverage for Floors 1-3, boss drops, trophies, and utility gear
- New suite MMO icon plus party, guild, resources, floors, recipes, skills, professions, combat, and guide icons
- Quest, suite status, module health, and resource-pack status icons for the 1.3.0 integration polish pass
- CrownsAPI pack metadata updated so `/capi pack download` can fetch the current pack onto the server
- Manual, ValhallaMMO-safe delivery remains the default

## Current Build

- File: `CrownsSuite-ResourcePack-1.5.0.zip`
- SHA1: `38ea1def8cf48afa624711276f39f7d023f1134b`

## Server Download Flow

CrownsAPI is configured with the GitHub raw URL and SHA1 for the current pack. Staff can run `/capi pack download` to download the zip into the server-side CrownsAPI resource-pack cache. The default config does not force a client prompt, and it keeps ValhallaMMO resource-pack behavior protected unless the server owner explicitly changes that setting.
