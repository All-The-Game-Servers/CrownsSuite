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

## 1.6.0 Focus

- Required-pack relaunch for Minecraft `1.21.11`
- Blockbench source models under `resource-pack/source/blockbench`
- Sharper 32x icons with stronger shading, glow, outlines, and material identity
- Complete validation against every plugin-side `lowlight/...` model path
- Contact-sheet preview generated at build time for visual QA
- CrownsAPI can resolve the latest pack from GitHub Releases with `/capi pack refresh`
- `/capi pack apply` sends the required pack to online players
- Rebuilt for Minecraft `1.21.11` resource-pack format `75`
- ValhallaMMO no longer blocks CrownsAPI; disable Valhalla's separate automatic pack prompt or publish a merged pack later

## Current Build

- File: `CrownsSuite-ResourcePack-1.6.0.zip`
- SHA1: `a511c036004513985f463427b986995f28b47095`

## Server Download Flow

CrownsAPI is configured for GitHub Releases. Publish `CrownsSuite-ResourcePack-1.6.0.zip` and `CrownsSuite-ResourcePack-1.6.0.sha1` as release assets, then run `/capi pack refresh` to resolve and cache the latest release pack. The pack is required by default and is sent automatically on join. Staff can run `/capi pack apply` to resend it to online players.
