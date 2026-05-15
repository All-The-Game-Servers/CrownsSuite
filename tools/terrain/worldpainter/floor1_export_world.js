// Export a CrownsTerrain Floor 1 WorldPainter project to a Minecraft world folder.

var worldFile = arguments[0];
var exportDirectory = arguments[1];

print("Loading WorldPainter project: " + worldFile);
var world = wp.getWorld()
    .fromFile(worldFile)
    .go();

print("Exporting Minecraft world folder: " + exportDirectory);
wp.exportWorld(world)
    .toDirectory(exportDirectory)
    .go();

print("World export complete: " + exportDirectory);
