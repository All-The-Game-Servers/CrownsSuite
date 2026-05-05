package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;

public final class SetMapFloorBuilder {
    private static final int TOWN_Y = 70;
    private static final int ARENA_Y = 78;

    private SetMapFloorBuilder() {
    }

    public static List<ChunkCoord> criticalChunks(FloorBlueprint blueprint) {
        List<TerrainPoint> points = new ArrayList<>();
        for (FloorBlueprint.Node node : blueprint.nodes()) {
            points.add(node.toPoint(blueprint.floor(), blueprint.worldName()));
        }
        return criticalChunks(points);
    }

    public static List<ChunkCoord> criticalChunks(List<TerrainPoint> points) {
        Map<String, TerrainPoint> map = pointMap(points);
        Set<ChunkCoord> chunks = new LinkedHashSet<>();
        TerrainPoint haven = point(map, "first-haven", "village", 0, 70, 0);
        TerrainPoint farms = point(map, "farm-gate", "road_marker", -190, 70, 125);
        TerrainPoint camp = point(map, "starter-camp", "camp", 275, 70, -120);
        TerrainPoint shrine = point(map, "starter-shrine", "shrine", -90, 72, -180);
        TerrainPoint waystone = point(map, "first-waystone", "waystone", 18, 70, 12);
        TerrainPoint northRoad = point(map, "north-road", "road_marker", 0, 70, -285);
        TerrainPoint approach = point(map, "arena-approach", "road_marker", 720, 76, 610);
        TerrainPoint arena = point(map, "first-gate-arena", "arena", 960, 78, 768);
        addChunkDisk(chunks, haven.x(), haven.z(), 180);
        addChunkDisk(chunks, farms.x(), farms.z(), 96);
        addChunkDisk(chunks, camp.x(), camp.z(), 80);
        addChunkDisk(chunks, shrine.x(), shrine.z(), 72);
        addChunkDisk(chunks, arena.x(), arena.z(), 128);
        addRoadChunks(chunks, haven, farms, 4);
        addRoadChunks(chunks, haven, camp, 4);
        addRoadChunks(chunks, haven, shrine, 4);
        addRoadChunks(chunks, haven, northRoad, 4);
        addRoadChunks(chunks, haven, approach, 4);
        addRoadChunks(chunks, approach, arena, 4);
        addChunkDisk(chunks, waystone.x(), waystone.z(), 48);
        return List.copyOf(chunks);
    }

    public static List<BlockOperation> operations(TerrainTheme theme, FloorBlueprint blueprint) {
        List<TerrainPoint> points = new ArrayList<>();
        for (FloorBlueprint.Node node : blueprint.nodes()) {
            points.add(node.toPoint(blueprint.floor(), blueprint.worldName()));
        }
        return operations(null, theme, points);
    }

    public static List<BlockOperation> operations(World world, TerrainTheme theme, List<TerrainPoint> points) {
        Map<String, TerrainPoint> map = pointMap(points);
        TerrainPoint haven = point(map, "first-haven", "village", 0, TOWN_Y, 0);
        TerrainPoint market = point(map, "market-square", "landmark", 18, TOWN_Y, 20);
        TerrainPoint farms = point(map, "farm-gate", "road_marker", -190, TOWN_Y, 125);
        TerrainPoint camp = point(map, "starter-camp", "camp", 275, TOWN_Y, -120);
        TerrainPoint shrine = point(map, "starter-shrine", "shrine", -90, TOWN_Y + 2, -180);
        TerrainPoint waystone = point(map, "first-waystone", "waystone", 18, TOWN_Y, 12);
        TerrainPoint northRoad = point(map, "north-road", "road_marker", 0, TOWN_Y, -285);
        TerrainPoint approach = point(map, "arena-approach", "road_marker", 720, ARENA_Y, 610);
        TerrainPoint arena = point(map, "first-gate-arena", "arena", 960, ARENA_Y, 768);
        List<BlockOperation> ops = new ArrayList<>();
        addTownTerrain(ops, haven);
        addRoad(ops, haven.x(), haven.z(), farms.x(), farms.z(), 5, TOWN_Y);
        addRoad(ops, haven.x(), haven.z(), camp.x(), camp.z(), 5, TOWN_Y);
        addRoad(ops, haven.x(), haven.z(), shrine.x(), shrine.z(), 5, TOWN_Y);
        addRoad(ops, haven.x(), haven.z(), northRoad.x(), northRoad.z(), 5, TOWN_Y);
        addRoad(ops, haven.x(), haven.z(), approach.x(), approach.z(), 6, TOWN_Y + 2);
        addRoad(ops, approach.x(), approach.z(), arena.x(), arena.z(), 7, ARENA_Y);
        addPlaza(ops, market.x(), TOWN_Y, market.z());
        addWaystone(ops, waystone.x(), TOWN_Y + 1, waystone.z(), theme);
        addTownHall(ops, -34, TOWN_Y + 1, -48);
        addGuildHall(ops, 42, TOWN_Y + 1, -48);
        addHouse(ops, -78, TOWN_Y + 1, -18, 18, 14, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
        addHouse(ops, -82, TOWN_Y + 1, 34, 20, 14, Material.OAK_PLANKS, Material.DARK_OAK_PLANKS);
        addHouse(ops, -22, TOWN_Y + 1, 58, 18, 16, Material.STRIPPED_OAK_LOG, Material.SPRUCE_PLANKS);
        addHouse(ops, 68, TOWN_Y + 1, 38, 20, 16, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
        addMarketStreet(ops, 52, TOWN_Y + 1, 8);
        addGatehouse(ops, 116, TOWN_Y + 1, -4);
        addWatchtower(ops, -118, TOWN_Y + 1, 72);
        addFarms(ops, farms.x(), TOWN_Y, farms.z());
        addCamp(ops, camp.x(), TOWN_Y, camp.z());
        addShrine(ops, shrine.x(), TOWN_Y + 2, shrine.z(), theme);
        addRoadMarker(ops, northRoad.x(), TOWN_Y + 1, northRoad.z());
        addRoadMarker(ops, approach.x(), ARENA_Y + 1, approach.z());
        addArena(ops, arena.x(), ARENA_Y, arena.z(), theme);
        addHeroTrees(ops);
        return ops;
    }

    public static void apply(World world, BlockOperation operation) {
        if (operation.y() <= world.getMinHeight() || operation.y() >= world.getMaxHeight()) {
            return;
        }
        world.getBlockAt(operation.x(), operation.y(), operation.z()).setType(operation.material(), false);
    }

    private static Map<String, TerrainPoint> pointMap(List<TerrainPoint> points) {
        Map<String, TerrainPoint> map = new LinkedHashMap<>();
        for (TerrainPoint point : points) {
            map.put(point.key().toLowerCase().replace('-', '_'), point);
        }
        return map;
    }

    private static TerrainPoint point(Map<String, TerrainPoint> map, String key, String type, int x, int y, int z) {
        TerrainPoint point = map.get(key.toLowerCase().replace('-', '_'));
        return point == null ? new TerrainPoint(1, "crowns_floor_1_v5", type, key, key, x, y, z) : point;
    }

    private static void addTownTerrain(List<BlockOperation> ops, TerrainPoint haven) {
        addPreparedRect(ops, haven.x() - 132, haven.z() - 110, 264, 224, TOWN_Y, Material.GRASS_BLOCK);
        addPreparedDisk(ops, haven.x(), haven.z(), 66, TOWN_Y, Material.STONE_BRICKS);
        addPreparedRect(ops, haven.x() - 64, haven.z() - 18, 148, 12, TOWN_Y, Material.COBBLESTONE);
        addPreparedRect(ops, haven.x() - 14, haven.z() - 88, 13, 176, TOWN_Y, Material.COBBLESTONE);
        addPreparedRect(ops, haven.x() - 118, haven.z() + 74, 42, 22, TOWN_Y, Material.GRASS_BLOCK);
        for (int x = haven.x() - 132; x <= haven.x() + 132; x++) {
            for (int z = haven.z() - 110; z <= haven.z() + 110; z++) {
                if ((x == haven.x() - 132 || x == haven.x() + 132 || z == haven.z() - 110 || z == haven.z() + 110)
                        && Math.floorMod(x + z, 3) != 0) {
                    ops.add(new BlockOperation(x, TOWN_Y + 1, z, Material.COBBLESTONE_WALL));
                }
            }
        }
    }

    private static void addPlaza(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedDisk(ops, x, z, 25, y, Material.POLISHED_ANDESITE);
        addRing(ops, x, y + 1, z, 9, Material.CUT_COPPER);
        addDiskTop(ops, x, z, 5, y + 1, Material.WATER);
        for (int yy = y + 2; yy <= y + 5; yy++) {
            ops.add(new BlockOperation(x, yy, z, yy == y + 5 ? Material.CUT_COPPER : Material.STONE_BRICKS));
        }
        ops.add(new BlockOperation(x, y + 6, z, Material.LANTERN));
    }

    private static void addTownHall(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x, z, 34, 24, y - 1, Material.STONE_BRICKS);
        addBuildingShell(ops, x, y, z, 34, 24, 7, Material.STONE_BRICKS, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
        addTower(ops, x + 4, y, z + 4, 6, 12, Material.STONE_BRICKS, Material.DARK_OAK_PLANKS);
        addTower(ops, x + 24, y, z + 4, 6, 12, Material.STONE_BRICKS, Material.DARK_OAK_PLANKS);
    }

    private static void addGuildHall(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x, z, 30, 22, y - 1, Material.STONE_BRICKS);
        addBuildingShell(ops, x, y, z, 30, 22, 6, Material.STRIPPED_SPRUCE_LOG, Material.OAK_PLANKS, Material.DARK_OAK_PLANKS);
    }

    private static void addHouse(List<BlockOperation> ops, int x, int y, int z, int width, int depth, Material wall, Material roof) {
        addPreparedRect(ops, x, z, width, depth, y - 1, Material.STONE_BRICKS);
        addBuildingShell(ops, x, y, z, width, depth, 5, Material.OAK_LOG, wall, roof);
        addPorch(ops, x + width / 2 - 3, y, z - 4, 7, 4);
    }

    private static void addMarketStreet(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x - 6, z - 24, 52, 54, y - 1, Material.POLISHED_ANDESITE);
        for (int i = 0; i < 4; i++) {
            int stallX = x + i * 12;
            int stallZ = z + (i % 2 == 0 ? -18 : 14);
            addPreparedRect(ops, stallX, stallZ, 9, 8, y - 1, Material.COBBLESTONE);
            for (int dx = 0; dx <= 8; dx++) {
                for (int dz = 0; dz <= 7; dz++) {
                    if (dx == 0 || dx == 8 || dz == 0 || dz == 7) {
                        ops.add(new BlockOperation(stallX + dx, y, stallZ + dz, Material.OAK_FENCE));
                    }
                    if (dx >= 1 && dx <= 7 && dz >= 1 && dz <= 6) {
                        ops.add(new BlockOperation(stallX + dx, y + 3, stallZ + dz, (dx + dz + i) % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL));
                    }
                }
            }
            ops.add(new BlockOperation(stallX + 4, y, stallZ + 4, Material.BARREL));
        }
    }

    private static void addGatehouse(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x - 10, z - 18, 34, 36, y - 1, Material.STONE_BRICKS);
        addTower(ops, x - 8, y, z - 10, 8, 12, Material.STONE_BRICKS, Material.DARK_OAK_PLANKS);
        addTower(ops, x + 12, y, z - 10, 8, 12, Material.STONE_BRICKS, Material.DARK_OAK_PLANKS);
        for (int yy = y; yy <= y + 8; yy++) {
            for (int xx = x; xx <= x + 11; xx++) {
                if (yy >= y + 6 || xx == x || xx == x + 11) {
                    ops.add(new BlockOperation(xx, yy, z - 2, Material.STONE_BRICKS));
                    ops.add(new BlockOperation(xx, yy, z + 2, Material.STONE_BRICKS));
                }
            }
        }
    }

    private static void addWatchtower(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x - 4, z - 4, 13, 13, y - 1, Material.STONE_BRICKS);
        addTower(ops, x, y, z, 9, 15, Material.OAK_LOG, Material.DARK_OAK_PLANKS);
    }

    private static void addFarms(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedRect(ops, x - 54, z - 34, 112, 72, y, Material.GRASS_BLOCK);
        for (int plot = 0; plot < 4; plot++) {
            int px = x - 48 + plot * 27;
            addPreparedRect(ops, px, z - 24, 22, 46, y, Material.FARMLAND);
            for (int dx = 2; dx < 20; dx++) {
                for (int dz = 2; dz < 44; dz++) {
                    if (dx == 10) {
                        ops.add(new BlockOperation(px + dx, y, z - 24 + dz, Material.WATER));
                    } else if (Math.floorMod(dx + dz + plot, 3) != 0) {
                        ops.add(new BlockOperation(px + dx, y + 1, z - 24 + dz, plot % 2 == 0 ? Material.WHEAT : Material.CARROTS));
                    }
                }
            }
        }
        addBuildingShell(ops, x + 28, y + 1, z + 24, 24, 16, 6, Material.OAK_LOG, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
    }

    private static void addCamp(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedDisk(ops, x, z, 28, y, Material.COARSE_DIRT);
        addDiskTop(ops, x, z, 12, y, Material.COBBLESTONE);
        ops.add(new BlockOperation(x, y + 1, z, Material.CAMPFIRE));
        addTent(ops, x - 18, y + 1, z - 8, Material.GREEN_WOOL);
        addTent(ops, x + 10, y + 1, z + 8, Material.BROWN_WOOL);
        for (int i = 0; i < 8; i++) {
            int lx = x - 24 + i * 7;
            ops.add(new BlockOperation(lx, y + 1, z + 22, Material.LANTERN));
        }
    }

    private static void addShrine(List<BlockOperation> ops, int x, int y, int z, TerrainTheme theme) {
        addPreparedDisk(ops, x, z, 26, y - 1, Material.MOSSY_STONE_BRICKS);
        addRing(ops, x, y, z, 15, Material.STONE_BRICKS);
        for (int dx : List.of(-8, 8)) {
            for (int dz : List.of(-8, 8)) {
                for (int yy = y; yy <= y + 6; yy++) {
                    ops.add(new BlockOperation(x + dx, yy, z + dz, Material.STONE_BRICKS));
                }
            }
        }
        for (int yy = y; yy <= y + 9; yy++) {
            ops.add(new BlockOperation(x, yy, z, yy == y + 7 ? theme.accent() : Material.MOSSY_STONE_BRICKS));
        }
        ops.add(new BlockOperation(x, y + 10, z, Material.LANTERN));
    }

    private static void addWaystone(List<BlockOperation> ops, int x, int y, int z, TerrainTheme theme) {
        addPreparedDisk(ops, x, z, 10, y - 1, Material.POLISHED_ANDESITE);
        for (int yy = y; yy <= y + 8; yy++) {
            Material material = yy == y + 5 ? theme.accent() : Material.STONE_BRICKS;
            ops.add(new BlockOperation(x, yy, z, material));
            if (yy <= y + 5) {
                ops.add(new BlockOperation(x + 1, yy, z, Material.MOSSY_STONE_BRICKS));
                ops.add(new BlockOperation(x - 1, yy, z, Material.MOSSY_STONE_BRICKS));
                ops.add(new BlockOperation(x, yy, z + 1, Material.MOSSY_STONE_BRICKS));
                ops.add(new BlockOperation(x, yy, z - 1, Material.MOSSY_STONE_BRICKS));
            }
        }
    }

    private static void addRoadMarker(List<BlockOperation> ops, int x, int y, int z) {
        addPreparedDisk(ops, x, z, 8, y - 1, Material.COBBLESTONE);
        for (int yy = y; yy <= y + 4; yy++) {
            ops.add(new BlockOperation(x, yy, z, Material.OAK_LOG));
        }
        ops.add(new BlockOperation(x, y + 5, z, Material.LANTERN));
    }

    private static void addArena(List<BlockOperation> ops, int x, int y, int z, TerrainTheme theme) {
        addPreparedDisk(ops, x, z, 74, y, Material.GRASS_BLOCK);
        addPreparedDisk(ops, x, z, 48, y, Material.STONE_BRICKS);
        addRing(ops, x, y + 1, z, 52, Material.MOSSY_STONE_BRICKS);
        addRing(ops, x, y + 2, z, 53, Material.STONE_BRICKS);
        addRing(ops, x, y + 3, z, 54, Material.STONE_BRICKS);
        addDiskTop(ops, x, z, 12, y + 1, Material.POLISHED_ANDESITE);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D;
            int px = x + (int) Math.round(Math.cos(angle) * 42.0D);
            int pz = z + (int) Math.round(Math.sin(angle) * 42.0D);
            for (int yy = y + 1; yy <= y + 8; yy++) {
                ops.add(new BlockOperation(px, yy, pz, yy == y + 7 ? theme.accent() : Material.STONE_BRICKS));
            }
            ops.add(new BlockOperation(px, y + 9, pz, Material.LANTERN));
        }
        addThresholdGate(ops, x, y + 1, z - 62, theme);
    }

    private static void addHeroTrees(List<BlockOperation> ops) {
        addTree(ops, -150, TOWN_Y, -135, 12);
        addTree(ops, 165, TOWN_Y, 104, 10);
        addTree(ops, 330, TOWN_Y, -142, 11);
    }

    private static void addBuildingShell(List<BlockOperation> ops, int x, int y, int z, int width, int depth, int height, Material frame, Material wall, Material roof) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                ops.add(new BlockOperation(x + dx, y, z + dz, Material.SPRUCE_PLANKS));
                boolean edge = dx == 0 || dz == 0 || dx == width - 1 || dz == depth - 1;
                boolean pillar = (dx == 0 || dx == width - 1) && (dz == 0 || dz == depth - 1);
                if (edge) {
                    for (int yy = y + 1; yy <= y + height; yy++) {
                        boolean doorway = dz == 0 && Math.abs(dx - width / 2) <= 1 && yy <= y + 3;
                        boolean window = yy == y + 3 && !pillar && Math.floorMod(dx + dz, 5) == 0;
                        if (doorway || window) {
                            if (window) {
                                ops.add(new BlockOperation(x + dx, yy, z + dz, Material.GLASS_PANE));
                            }
                        } else {
                            ops.add(new BlockOperation(x + dx, yy, z + dz, pillar ? frame : wall));
                        }
                    }
                }
            }
        }
        for (int layer = 0; layer <= 4; layer++) {
            for (int dx = -layer; dx < width + layer; dx++) {
                for (int dz = -layer; dz < depth + layer; dz++) {
                    boolean edge = dx == -layer || dz == -layer || dx == width + layer - 1 || dz == depth + layer - 1;
                    if (edge || layer >= 3) {
                        ops.add(new BlockOperation(x + dx, y + height + 1 + layer, z + dz, roof));
                    }
                }
            }
        }
    }

    private static void addTower(List<BlockOperation> ops, int x, int y, int z, int size, int height, Material wall, Material roof) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                ops.add(new BlockOperation(x + dx, y, z + dz, Material.STONE_BRICKS));
                boolean edge = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                if (edge) {
                    for (int yy = y + 1; yy <= y + height; yy++) {
                        ops.add(new BlockOperation(x + dx, yy, z + dz, wall));
                    }
                }
                if (edge || dx == size / 2 || dz == size / 2) {
                    ops.add(new BlockOperation(x + dx, y + height + 1, z + dz, roof));
                }
            }
        }
    }

    private static void addPorch(List<BlockOperation> ops, int x, int y, int z, int width, int depth) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                ops.add(new BlockOperation(x + dx, y, z + dz, Material.SPRUCE_SLAB));
                if ((dx == 0 || dx == width - 1) && dz == 0) {
                    ops.add(new BlockOperation(x + dx, y + 1, z + dz, Material.OAK_FENCE));
                    ops.add(new BlockOperation(x + dx, y + 2, z + dz, Material.LANTERN));
                }
            }
        }
    }

    private static void addTent(List<BlockOperation> ops, int x, int y, int z, Material cloth) {
        for (int dx = 0; dx < 11; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                ops.add(new BlockOperation(x + dx, y, z + dz, Material.COARSE_DIRT));
                int roofY = y + 1 + Math.min(dx, 10 - dx);
                if (dx <= 5) {
                    ops.add(new BlockOperation(x + dx, roofY, z + dz, cloth));
                }
            }
        }
    }

    private static void addThresholdGate(List<BlockOperation> ops, int x, int y, int z, TerrainTheme theme) {
        for (int dx = -13; dx <= 13; dx++) {
            for (int yy = 0; yy <= 16; yy++) {
                boolean pillar = Math.abs(dx) >= 9;
                boolean arch = yy >= 12 && Math.abs(dx) <= 13;
                if (pillar || arch) {
                    ops.add(new BlockOperation(x + dx, y + yy, z, yy == 13 && Math.abs(dx) <= 3 ? theme.accent() : Material.STONE_BRICKS));
                    ops.add(new BlockOperation(x + dx, y + yy, z + 1, Material.MOSSY_STONE_BRICKS));
                }
            }
        }
    }

    private static void addTree(List<BlockOperation> ops, int x, int y, int z, int height) {
        for (int yy = y + 1; yy <= y + height; yy++) {
            ops.add(new BlockOperation(x, yy, z, yy % 4 == 0 ? Material.STRIPPED_OAK_LOG : Material.OAK_LOG));
            if (yy <= y + 4) {
                ops.add(new BlockOperation(x + 1, yy, z, Material.OAK_LOG));
                ops.add(new BlockOperation(x - 1, yy, z, Material.OAK_LOG));
                ops.add(new BlockOperation(x, yy, z + 1, Material.OAK_LOG));
                ops.add(new BlockOperation(x, yy, z - 1, Material.OAK_LOG));
            }
        }
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                int dist = (int) Math.round(Math.hypot(dx, dz));
                if (dist <= 8) {
                    int leafY = y + height - 2 + (dist <= 4 ? 2 : 0);
                    ops.add(new BlockOperation(x + dx, leafY, z + dz, dist >= 7 ? Material.AZALEA_LEAVES : Material.OAK_LEAVES));
                    if (dist <= 5) {
                        ops.add(new BlockOperation(x + dx, leafY + 1, z + dz, Material.OAK_LEAVES));
                    }
                }
            }
        }
    }

    private static void addRoad(List<BlockOperation> ops, int x1, int z1, int x2, int z2, int radius, int y) {
        int samples = Math.max(1, (int) Math.round(Math.hypot(x2 - x1, z2 - z1) / 3.0D));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            addPreparedDisk(ops, x, z, radius, y, Math.floorMod(i, 5) == 0 ? Material.ANDESITE : Material.COBBLESTONE);
            if (i % 9 == 0) {
                ops.add(new BlockOperation(x + radius + 1, y + 1, z, Material.LANTERN));
                ops.add(new BlockOperation(x - radius - 1, y + 1, z, Material.LANTERN));
            }
        }
    }

    private static void addPreparedRect(List<BlockOperation> ops, int x, int z, int width, int depth, int y, Material top) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                prepareColumn(ops, x + dx, z + dz, y, top);
            }
        }
    }

    private static void addPreparedDisk(List<BlockOperation> ops, int x, int z, int radius, int y, Material top) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.hypot(dx, dz) <= radius) {
                    prepareColumn(ops, x + dx, z + dz, y, top);
                }
            }
        }
    }

    private static void addDiskTop(List<BlockOperation> ops, int x, int z, int radius, int y, Material material) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.hypot(dx, dz) <= radius) {
                    ops.add(new BlockOperation(x + dx, y, z + dz, material));
                }
            }
        }
    }

    private static void addRing(List<BlockOperation> ops, int x, int y, int z, int radius, Material material) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.hypot(dx, dz);
                if (distance >= radius - 1.5D && distance <= radius + 1.5D) {
                    ops.add(new BlockOperation(x + dx, y, z + dz, material));
                }
            }
        }
    }

    private static void prepareColumn(List<BlockOperation> ops, int x, int z, int y, Material top) {
        for (int yy = y - 6; yy < y; yy++) {
            ops.add(new BlockOperation(x, yy, z, yy <= y - 4 ? Material.STONE : Material.DIRT));
        }
        ops.add(new BlockOperation(x, y, z, top));
        for (int yy = y + 1; yy <= y + 16; yy++) {
            ops.add(new BlockOperation(x, yy, z, Material.AIR));
        }
    }

    private static void addChunkDisk(Set<ChunkCoord> chunks, int x, int z, int radiusBlocks) {
        int chunkRadius = Math.max(1, radiusBlocks >> 4);
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                if (Math.hypot(dx, dz) <= chunkRadius + 0.5D) {
                    chunks.add(new ChunkCoord(cx + dx, cz + dz));
                }
            }
        }
    }

    private static void addRoadChunks(Set<ChunkCoord> chunks, TerrainPoint a, TerrainPoint b, int radiusChunks) {
        int samples = Math.max(1, (int) Math.round(Math.hypot(b.x() - a.x(), b.z() - a.z()) / 32.0D));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int cx = ((int) Math.round(a.x() + (b.x() - a.x()) * t)) >> 4;
            int cz = ((int) Math.round(a.z() + (b.z() - a.z()) * t)) >> 4;
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    chunks.add(new ChunkCoord(cx + dx, cz + dz));
                }
            }
        }
    }

    public record ChunkCoord(int x, int z) {
    }

    public record BlockOperation(int x, int y, int z, Material material) {
    }
}
