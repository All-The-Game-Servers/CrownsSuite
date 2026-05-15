// WorldPainter wpscript entrypoint for the CrownsTerrain Floor 1 slice.
//
// This script is intentionally small and mask-driven. If a local WorldPainter
// release changes scripting syntax, keep the masks/report stable and update
// this adapter only.

var heightMapFile = arguments[0];
var worldFile = arguments[1];
var worldName = arguments.length > 2 ? arguments[2] : "crowns_floor_1_wp_slice";

print("Creating WorldPainter project for " + worldName);
print("Heightmap: " + heightMapFile);
print("Output: " + worldFile);

var heightMap = wp.getHeightMap()
    .fromFile(heightMapFile)
    .go();

var world = wp.createWorld()
    .fromHeightMap(heightMap)
    .fromLevels(0, 255)
    .toLevels(44, 168)
    .withWaterLevel(62)
    .go();

wp.saveWorld(world)
    .toFile(worldFile)
    .go();

print("WorldPainter project written: " + worldFile);
