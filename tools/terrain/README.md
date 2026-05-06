# CrownsTerrain Converter Pipeline

This folder contains offline converters for turning authored voxel/block work into CrownsTerrain `.ctpl` templates.

## Target Workflow

1. Build a structure in MagicaVoxel or Blender.
2. Export to `.vox` or block JSON.
3. Convert to `.ctpl`.
4. Copy the `.ctpl` file into `plugins/CrownsTerrain/structures`.
5. Run `/cterrain structure reload`.
6. Check `/cterrain structure list` and `/cterrain structure info <key>`.

## Commands

Convert MagicaVoxel:

```powershell
python tools\terrain\vox_to_ctpl.py D:\CrownsSuiteTools\Projects\first_haven_house.vox D:\CrownsSuiteTools\Exports\first_haven_house.ctpl
```

Convert block JSON:

```powershell
python tools\terrain\json_to_ctpl.py D:\CrownsSuiteTools\Exports\first_haven_house.json D:\CrownsSuiteTools\Exports\first_haven_house.ctpl
```

Export from Blender:

```powershell
& "D:\CrownsSuiteTools\Apps\Blender-5.1.1\blender-5.1.1-windows-x64\blender.exe" --background my_scene.blend --python tools\terrain\blender_export_blocks.py -- D:\CrownsSuiteTools\Exports\my_scene.json
```

## Format Notes

- `.ctpl` is the current CrownsTerrain template format: key, anchor, palette, and stacked layers.
- The plugin now loads bundled templates plus custom templates from `plugins/CrownsTerrain/structures`.
- Custom templates can override bundled keys, which is useful for replacing prototype structures without rebuilding the jar.

