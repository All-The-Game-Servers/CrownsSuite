# Crowns Suite Resource Pack

This folder contains the source and build tooling for the `Crowns Suite 1.5.0` resource pack.

## What It Covers

- `CrownsAPI` suite-shell icons, suite profile, and resource-pack surfaces
- `CrownsEconomy` hub and gambling icons
- `CrownsAdmin` staff-tool and dashboard icons
- `CrownsEvents` Nether Week and Endfall item models and live-moment surfaces
- `CrownsDrugs` raw, packaged, and recipe/equipment item families
- `CrownsMMO` floor resources, boss trophies, adventurer gear, party/guild icons, and MMO hub identity
- `CrownsTerrain` terrain hub, custom village, camp, road marker, waystone, shrine, arena, landmark, and floor-theme icons

## Build

Run:

```powershell
python resource-pack\tools\generate_resource_pack.py
```

That generates:

- source pack at `resource-pack/CrownsSuite-ResourcePack-1.5.0`
- distributable zip at `build/resource-pack/CrownsSuite-ResourcePack-1.5.0.zip`
- mirrored public zip at `downloads/CrownsSuite-ResourcePack-1.5.0.zip`
- SHA1 files at `build/resource-pack/CrownsSuite-ResourcePack-1.5.0.sha1` and `downloads/CrownsSuite-ResourcePack-1.5.0.sha1`
- model/asset index at `resource-pack/ASSET_INDEX.md`

## Server Install

1. Commit or upload `downloads/CrownsSuite-ResourcePack-1.5.0.zip`.
2. Copy the SHA1 from `downloads/CrownsSuite-ResourcePack-1.5.0.sha1`.
3. Keep the CrownsAPI resource-pack config pointed at the GitHub raw download URL and matching SHA1.
4. Run `/capi pack download` in-game or console to download the pack onto the server cache.
5. Keep client delivery manual unless you intentionally enable prompts; the default is ValhallaMMO-safe.

## Notes

- Event items already use stable `lowlight/...` model ids in code.
- Suite, economy, admin, drugs, and MMO menu icons now target stable `lowlight/...` item-model ids.
- The pack is designed for `32x` readability with a `Dark Arcane` visual direction.
- `1.5.0` is a full Dark Arcane redraw with complete CrownsMMO Floors 1-3 coverage.
