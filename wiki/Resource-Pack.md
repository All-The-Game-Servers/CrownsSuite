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

## 1.7.0 Focus

- Required-pack relaunch for Minecraft `1.21.11`
- Full 3D dark-fantasy redraw under the stable `lowlight/...` model paths
- Blockbench source libraries under `resource-pack/source/blockbench`
- Cube-based item models with family texture atlases instead of static square PNG icons
- Physical-looking relics, tools, trophies, tokens, gear pieces, crates, scrolls, bottles, compasses, and seals
- Complete validation against every plugin-side `lowlight/...` model path
- Contact-sheet preview generated at build time for visual QA
- CrownsAPI can resolve the latest pack from GitHub Releases with `/capi pack refresh`
- `/capi pack apply` sends the required pack to online players
- Rebuilt for Minecraft `1.21.11` resource-pack format `75`
- ValhallaMMO no longer blocks CrownsAPI; disable Valhalla's separate automatic pack prompt or publish a merged pack later

## Current Build

- File: `CrownsSuite-ResourcePack-1.7.0.zip`
- SHA1: `16c23b995647afbce1cb2495548e34d0e92b4e2b`

## Server Download Flow

CrownsAPI is configured for GitHub Releases. Publish `CrownsSuite-ResourcePack-1.7.0.zip` and `CrownsSuite-ResourcePack-1.7.0.sha1` as release assets, then run `/capi pack refresh` to resolve and cache the latest release pack. The pack is required by default and is sent automatically on join. Staff can run `/capi pack apply` to resend it to online players.
