# Crowns Suite Resource Pack

This folder contains the source and build tooling for the `Crowns Suite 1.1.0` resource pack.

## What It Covers

- `CrownsAPI` suite-shell icons
- `CrownsEconomy` hub and gambling icons
- `CrownsAdmin` staff-tool icons
- `CrownsEvents` Nether Week and Endfall item models
- `CrownsDrugs` raw and packaged item families

## Build

Run:

```powershell
python resource-pack\tools\generate_resource_pack.py
```

That generates:

- source pack at `resource-pack/CrownsSuite-ResourcePack-1.1.0`
- distributable zip at `build/resource-pack/CrownsSuite-ResourcePack-1.1.0.zip`
- SHA1 file at `build/resource-pack/CrownsSuite-ResourcePack-1.1.0.sha1`
- model/asset index at `resource-pack/ASSET_INDEX.md`

## Server Install

1. Upload the generated zip to your pack host.
2. Copy the SHA1 from the generated `.sha1` file.
3. Set the server resource-pack URL and hash in `server.properties` or your proxy/panel.
4. Restart or reload the server so clients receive the new pack prompt.

## Notes

- Event items already use stable `lowlight/...` model ids in code.
- Suite, economy, admin, and drugs menu icons now also target stable `lowlight/...` item-model ids.
- The pack is designed for `32x` readability with a `Dark Arcane` visual direction.
