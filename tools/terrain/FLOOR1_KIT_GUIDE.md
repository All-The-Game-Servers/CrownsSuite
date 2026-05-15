# CrownsTerrain Floor 1 Kit Guide

Floor 1 uses a hybrid workflow:

- Blender creates/editable block-authored structure and route-stamp sources.
- `build_floor1_kit.ps1` exports Blender collections to JSON and `.ctpl`.
- `render_floor1_previews.ps1` creates offline PNG previews and layout QA maps.
- CrownsTerrain places `.ctpl` files at runtime; Blender is never required on the server.

## Visual Direction

- First Haven should feel like a high-fantasy MMO starter town, not a vanilla village clone.
- Civic buildings should be larger, taller, and more intentional than houses.
- Residential buildings should have porches, roof breaks, yards, gardens, and small asymmetry.
- Market spaces should read clearly from above with awnings, crates, counters, and road frontage.
- Farming pieces should include terraces, water channels, barns, fences, and useful open space.
- Arena pieces should communicate a threshold: road, staging, gate, platform, and boundary.

## Offline QA Loop

1. Run `powershell -ExecutionPolicy Bypass -File tools\terrain\build_floor1_kit.ps1`.
2. Run `powershell -ExecutionPolicy Bypass -File tools\terrain\render_floor1_previews.ps1`.
3. Open `build\terrain-preview\floor1-kit-contact-sheet.png`.
4. Open `build\terrain-preview\floor1-layout.png`.
5. Check `build\terrain-preview\floor1-layout-report.json` for likely overlaps.
6. Improve `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\floor1_kit.blend` or the generator, then repeat.

## Known Review Targets

- Reduce simple single-box buildings over time.
- Add more roofline variation and vertical accents.
- Add smaller props around large buildings so they do not read as isolated blocks.
- Keep road and plaza stamps from becoming large flat gray fields.
- Use the layout report before in-game testing so obvious spacing mistakes are caught early.
