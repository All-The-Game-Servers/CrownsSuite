# Crowns Suite Resource Pack

This folder contains the source and build tooling for the `Crowns Suite 1.8.1` required resource pack, rebuilt for Minecraft Java `26.1.2` resource pack format `84`.

## What It Covers

- `CrownsAPI` suite-shell icons, suite profile, and resource-pack surfaces.
- `CrownsMagic` spellbook, focus, Elemental/Restoration/Astral school icons, starter spell icons, and v0.3 unlockable spell icons.
- `CrownsSwords` skillbook, training blade, Excalibur, Flash/Guard/Phantom style icons, starter sword-art icons, and v0.3 unlockable sword-art icons.
- Compatibility assets for older stable `lowlight/...` paths while the suite relaunch continues.
- Blockbench/source-library support for the 3D dark-fantasy redraw pipeline.

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

- source pack at `resource-pack/CrownsSuite-ResourcePack-1.8.1`
- distributable zip at `build/resource-pack/CrownsSuite-ResourcePack-1.8.1.zip`
- mirrored public zip at `downloads/CrownsSuite-ResourcePack-1.8.1.zip`
- SHA1 files at `build/resource-pack/CrownsSuite-ResourcePack-1.8.1.sha1` and `downloads/CrownsSuite-ResourcePack-1.8.1.sha1`
- review sheet at `build/resource-pack/CrownsSuite-ResourcePack-1.8.1-contact-sheet.png`
- model report at `build/resource-pack/CrownsSuite-ResourcePack-1.8.1-model-report.json`
- model/asset index at `resource-pack/ASSET_INDEX.md`

## Server Install

1. Commit `downloads/CrownsSuite-ResourcePack-1.8.1.zip` and `downloads/CrownsSuite-ResourcePack-1.8.1.sha1`.
2. Publish both files to a GitHub Release.
3. Run `/capi pack refresh` so CrownsAPI resolves the latest release asset, updates SHA1 metadata, and downloads the pack onto the server cache.
4. Run `/capi pack apply` or let join handling send the required pack automatically.
5. If ValhallaMMO is installed, disable Valhalla's separate automatic pack prompt or publish a merged pack later.

## Notes

- Magic and Swords use stable `lowlight/...` model ids in code.
- Compatibility assets for old suite paths are kept in the pack, but the old plugins are not active on `master`.
- `1.8.1` adds the dedicated Excalibur model as the first higher-polish sword-art test weapon target.
- The visual direction is `3D Dark Fantasy Relics`: aged metal, carved wood, parchment, glass, obsidian, ember/void glow, and worn gold trim.
- The generator validates every discovered plugin-side `lowlight/...` model path before exporting the zip.
