# Crowns Suite Resource Pack

This folder contains the source and build tooling for the `Crowns Suite 1.7.0` required resource pack, rebuilt for Minecraft Java `1.21.11` resource pack format `75`.

## What It Covers

- `CrownsAPI` suite-shell icons, suite profile, and resource-pack surfaces
- `CrownsEconomy` hub and gambling icons
- `CrownsAdmin` staff-tool and dashboard icons
- `CrownsEvents` Nether Week and Endfall item models and live-moment surfaces
- `CrownsDrugs` raw, packaged, and recipe/equipment item families
- `CrownsMMO` floor resources, boss trophies, adventurer gear, party/guild icons, and MMO hub identity
- `CrownsTerrain` terrain hub, custom village, camp, road marker, waystone, shrine, arena, landmark, and floor-theme icons
- Blockbench source libraries for the 3D dark-fantasy redraw pipeline under `resource-pack/source/blockbench`

## Build

Run:

```powershell
python resource-pack\tools\generate_resource_pack.py
```

Open the editable Blockbench sources:

```powershell
powershell -ExecutionPolicy Bypass -File resource-pack\tools\open_blockbench_sources.ps1
```

That generates:

- source pack at `resource-pack/CrownsSuite-ResourcePack-1.7.0`
- distributable zip at `build/resource-pack/CrownsSuite-ResourcePack-1.7.0.zip`
- mirrored public zip at `downloads/CrownsSuite-ResourcePack-1.7.0.zip`
- SHA1 files at `build/resource-pack/CrownsSuite-ResourcePack-1.7.0.sha1` and `downloads/CrownsSuite-ResourcePack-1.7.0.sha1`
- review sheet at `build/resource-pack/CrownsSuite-ResourcePack-1.7.0-contact-sheet.png`
- model report at `build/resource-pack/CrownsSuite-ResourcePack-1.7.0-model-report.json`
- model/asset index at `resource-pack/ASSET_INDEX.md`

## Server Install

1. Commit `downloads/CrownsSuite-ResourcePack-1.7.0.zip` and `downloads/CrownsSuite-ResourcePack-1.7.0.sha1`.
2. Publish both files to a GitHub Release.
3. Run `/capi pack refresh` so CrownsAPI resolves the latest release asset, updates SHA1 metadata, and downloads the pack onto the server cache.
4. Run `/capi pack apply` or let join handling send the required pack automatically.
5. If ValhallaMMO is installed, disable Valhalla's separate automatic pack prompt or publish a merged pack later.

## Notes

- Event items already use stable `lowlight/...` model ids in code.
- Suite, economy, admin, drugs, and MMO menu icons now target stable `lowlight/...` item-model ids.
- `1.7.0` replaces the old generated 32x square icons with cube-based 3D item models and family texture atlases.
- The visual direction is `3D Dark Fantasy Relics`: aged metal, carved wood, parchment, glass, obsidian, ember/void glow, and worn gold trim.
- The generator validates every discovered plugin-side `lowlight/...` model path before exporting the zip.
