# Installation

## Plugin Order

1. CrownsAPI
2. CrownsEconomy
3. CrownsAdmin
4. CrownsEvents
5. CrownsDrugs
6. CrownsTerrain
7. CrownsMMO

## Recommended Stack

- Minimum viable suite: `CrownsAPI` plus any one feature plugin.
- Recommended general SMP stack: `CrownsAPI`, `CrownsEconomy`, `CrownsAdmin`, and `CrownsEvents`.
- Recommended Season 1 Endfall stack: `CrownsAPI`, `CrownsEconomy`, `CrownsAdmin`, and `CrownsEvents`; CrownsMMO and CrownsTerrain are not required.
- Recommended Season 2 / MMO stack: all current jars, with `CrownsTerrain` installed before players use `/cmmo start`.
- `CrownsMMO` can run without optional modules, but it will show degraded status when Terrain, Economy, Events, Admin, or Drugs are missing.

## Data

- Shared database lives under `plugins/CrownsAPI`
- Existing monolith data should be migrated into the shared suite database path
- Always back up your database before upgrading

## Rollout Checklist

1. Back up `plugins/CrownsAPI/crowns.db` and old Crowns plugin folders.
2. Install only the current jars from `downloads`.
3. Start the server and run `/capi status` or `/capi modules`.
4. Confirm `/capi downloads` lists the expected jar/resource-pack versions.
5. For Season 1 Endfall, run `/events admin dryrun end-opening`.
6. Run `/events admin activate end-opening` only when staff are ready to prepare Endfall.
7. Run `/events admin schedule end-opening <yyyy-MM-dd HH:mm>` or `/events admin start end-opening`.
8. For Season 2 / MMO testing, build/export the WorldPainter Floor 1 slice first. CrownsTerrain can auto-copy the cached export from `terrain.floors.1.worldpainter.install-source-folder`.
9. Run `/cterrain admin generate 1`.
10. Wait for `/cterrain admin status 1` to show a player-ready state, then run `/cterrain verify floor 1`.
11. Test `/cmmo status`, then `/cmmo start`.

## Resource Pack

- CrownsAPI provides required resource-pack delivery through GitHub Releases
- Publish the current pack zip and `.sha1` as GitHub Release assets
- Run `/capi pack refresh` to resolve, cache, and verify the latest release pack
- Run `/capi pack apply` to resend the required pack to online players; joining players receive it automatically
- Current pack file: `CrownsSuite-ResourcePack-1.6.0.zip`
- Current pack SHA1: `a511c036004513985f463427b986995f28b47095`
- Current target: Minecraft `1.21.11` resource-pack format `75`
- CrownsAPI is the required pack owner. If ValhallaMMO is installed, disable Valhalla's separate automatic pack prompt or publish a merged pack later.

## CrownsMMO / Terrain Relaunch Notes

- CrownsTerrain `1.8.5` defaults Floor 1 terrain testing to the WorldPainter-backed world `crowns_floor_1_wp_slice`, not the server's existing `world`, `crowns_floor_1`, `crowns_floor_1_v2`, `crowns_floor_1_v3`, `crowns_floor_1_v4`, `crowns_floor_1_v5`, or `crowns_floor_1_v6`.
- Run `tools\terrain\worldpainter\build_floor1_masks.ps1` first and inspect `floor1-composite-preview.png`.
- Build/export the WorldPainter project after installing WorldPainter to `D:\CrownsSuiteTools\Apps\WorldPainter` or setting `WORLDPAINTER_HOME`.
- Keep the exported `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2\crowns_floor_1_wp_slice` folder available on the server machine. CrownsTerrain auto-installs it into the server root when `/cterrain admin generate 1` runs, or you can run `/cterrain admin installsource 1` manually.
- Run `/cterrain admin generate 1` before player testing, then wait for `/cterrain admin status 1` to show `CRITICAL_READY`.
- Run `/cterrain verify floor 1` to confirm generation status plus physical First Haven, road, farm, shrine, waystone, camp, and arena blocks.
- New adventurers can run `/cmmo start` to enter First Haven and begin the Floor 1 path.
- Use `/cterrain floor status 1`, `/cterrain floor anchors 1`, and `/cterrain floor qa 1` for the new Floor Runtime Platform checks.
- CrownsMMO `1.8.0` routes `/cmmo start`, `/cmmo floor 1`, floor deaths, and reconnect recovery through safe CrownsTerrain runtime anchors once Floor 1 is ready.

## CrownsTerrain Structure Pipeline

- For the WorldPainter macro terrain layer, run `powershell -ExecutionPolicy Bypass -File tools/terrain/worldpainter/build_floor1_masks.ps1`.
- Check the local WorldPainter install with `powershell -ExecutionPolicy Bypass -File tools/terrain/worldpainter/verify_worldpainter_install.ps1`.
- Once WorldPainter is installed, run `tools/terrain/worldpainter/build_floor1_worldpainter.ps1` and `tools/terrain/worldpainter/export_floor1_world.ps1`; the default export target is `D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2\crowns_floor_1_wp_slice`, which CrownsTerrain uses as the local install cache.
- For the bundled Floor 1 Blender kit, run `powershell -ExecutionPolicy Bypass -File tools/terrain/build_floor1_kit.ps1`. This writes the editable source to `D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit\floor1_kit.blend` and bundles generated `fh_*` `.ctpl` templates into CrownsTerrain.
- To review Floor 1 without a server, run `powershell -ExecutionPolicy Bypass -File tools/terrain/render_floor1_previews.ps1` and open `build/terrain-preview/floor1-kit-contact-sheet.png` plus `build/terrain-preview/floor1-layout.png`.
- Use `py tools/terrain/vox_to_ctpl.py` to convert MagicaVoxel `.vox` files into CrownsTerrain `.ctpl` templates.
- Use `tools/terrain/blender_export_blocks.py` and `tools/terrain/json_to_ctpl.py` for Blender cube/block scenes.
- Copy finished `.ctpl` files into the folder shown by `/cterrain structure folder`.
- Run `/cterrain structure reload`, then confirm with `/cterrain structure list` and `/cterrain structure info <key>`.
- Use `/cterrain studio wand` to select Creative builds in-game, `/cterrain studio capture <key>` to save them as `.ctpl`, and `/cterrain studio preview/place/confirm` to test them safely.
