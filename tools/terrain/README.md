# CrownsTerrain Converter Pipeline

This folder contains offline converters for turning authored voxel/block work into CrownsTerrain `.ctpl` templates.

It now also contains the Floor 1 macro-terrain pipeline:

- WorldPainter owns the broad map: height, rivers, biome paint, readable valleys/routes, and the 2k vertical-slice export.
- Blender owns authored buildings and stamps.
- CrownsTerrain owns runtime readiness, anchors, verification, and `.ctpl` overlays during pregeneration.

## Target Workflow

1. Build a structure in MagicaVoxel or Blender.
2. Export to `.vox` or block JSON.
3. Convert to `.ctpl`.
4. Copy the `.ctpl` file into `plugins/CrownsTerrain/structures`.
5. Run `/cterrain structure reload`.
6. Check `/cterrain structure list` and `/cterrain structure info <key>`.

## Commands

Build the generated editable Floor 1 Blender kit and bundle its `.ctpl` output into CrownsTerrain:

```powershell
powershell -ExecutionPolicy Bypass -File tools\terrain\build_floor1_kit.ps1
```

Render offline Floor 1 QA previews:

```powershell
powershell -ExecutionPolicy Bypass -File tools\terrain\render_floor1_previews.ps1
```

Build the WorldPainter Floor 1 macro masks:

```powershell
powershell -ExecutionPolicy Bypass -File tools\terrain\worldpainter\build_floor1_masks.ps1
```

Check whether WorldPainter scripting is installed on the tools drive:

```powershell
powershell -ExecutionPolicy Bypass -File tools\terrain\worldpainter\verify_worldpainter_install.ps1
```

Build and export the WorldPainter project after installing WorldPainter:

```powershell
powershell -ExecutionPolicy Bypass -File tools\terrain\worldpainter\build_floor1_worldpainter.ps1
powershell -ExecutionPolicy Bypass -File tools\terrain\worldpainter\export_floor1_world.ps1
```

Default kit outputs:

- Blender source: `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\floor1_kit.blend`
- JSON exports: `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\exports\json`
- `.ctpl` exports: `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\exports\ctpl`
- Manifest/report: `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\exports`
- Offline previews: `build\terrain-preview\floor1-kit-contact-sheet.png` and `build\terrain-preview\floor1-layout.png`
- WorldPainter masks: `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\Floor1Slice`
- WorldPainter test export: `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2\crowns_floor_1_wp_slice`

## WorldPainter + CrownsTerrain Runtime Flow

1. Run `build_floor1_masks.ps1` and inspect `floor1-composite-preview.png`.
2. Install WorldPainter under `D:\CrownsSuiteTools\Apps\WorldPainter`, or set `WORLDPAINTER_HOME`.
3. Run `build_floor1_worldpainter.ps1` to create `crowns_floor_1_wp_slice.world`.
4. Run `export_floor1_world.ps1` to export the Minecraft world folder into `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2\crowns_floor_1_wp_slice`.
5. Start the server and run `/cterrain admin generate 1`.
6. CrownsTerrain auto-installs the cached export from `terrain.floors.1.worldpainter.install-source-folder` if the server-root world folder is missing.
7. Run `/cterrain verify floor 1`.

CrownsTerrain will not create a replacement world for `worldpainter-plus-ctpl` floors. If both the server-root world folder and the configured install-source folder are missing, `/cterrain admin create 1` and `/cterrain admin generate 1` fail loudly instead of generating accidental vanilla terrain. Use `/cterrain admin installsource 1` to install the cached map manually without starting pregeneration.

Convert MagicaVoxel:

```powershell
py tools\terrain\vox_to_ctpl.py D:\CrownsSuiteTools\Projects\first_haven_house.vox D:\CrownsSuiteTools\Exports\first_haven_house.ctpl
```

Convert block JSON:

```powershell
py tools\terrain\json_to_ctpl.py D:\CrownsSuiteTools\Exports\first_haven_house.json D:\CrownsSuiteTools\Exports\first_haven_house.ctpl
```

Export from Blender:

```powershell
& "D:\CrownsSuiteTools\Apps\Blender-5.1.1\blender-5.1.1-windows-x64\blender.exe" --background my_scene.blend --python tools\terrain\blender_export_blocks.py -- D:\CrownsSuiteTools\Exports\my_scene.json
```

Export each top-level Blender collection as its own template JSON:

```powershell
& "D:\CrownsSuiteTools\Apps\Blender-5.1.1\blender-5.1.1-windows-x64\blender.exe" --background my_scene.blend --python tools\terrain\blender_export_blocks.py -- D:\CrownsSuiteTools\Exports\Floor1Kit --collections --manifest D:\CrownsSuiteTools\Exports\floor1_manifest.json
```

## Format Notes

- `.ctpl` is the current CrownsTerrain template format: key, anchor, palette, and stacked layers.
- The Blender exporter preserves `minecraft_blockdata` custom properties for useful block states such as stairs, slabs, fences, lanterns, water, crops, panes, and logs.
- The plugin now loads bundled templates plus custom templates from `plugins/CrownsTerrain/structures`.
- Custom templates can override bundled keys, which is useful for replacing prototype structures without rebuilding the jar.
