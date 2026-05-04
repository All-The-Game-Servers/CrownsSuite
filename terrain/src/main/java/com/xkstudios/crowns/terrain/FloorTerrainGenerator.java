package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

public class FloorTerrainGenerator extends ChunkGenerator {
    private final int floor;
    private final String worldName;
    private final TerrainTheme theme;
    private final List<TerrainPoint> villages;
    private final TerrainPoint arena;
    private final List<TerrainPoint> landmarks;
    private final List<TerrainPoint> livingPoints;
    private final List<StructurePlacement> structurePlacements;

    public FloorTerrainGenerator(int floor, String worldName, TerrainTheme theme, List<TerrainPoint> villages, TerrainPoint arena, List<TerrainPoint> landmarks, List<TerrainPoint> livingPoints, List<StructurePlacement> structurePlacements) {
        this.floor = floor;
        this.worldName = worldName;
        this.theme = theme;
        this.villages = List.copyOf(villages);
        this.arena = arena;
        this.landmarks = List.copyOf(landmarks);
        this.livingPoints = List.copyOf(livingPoints);
        this.structurePlacements = List.copyOf(structurePlacements);
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int surface = Math.min(maxY - 16, this.surfaceHeight(worldX, worldZ));
                this.generateColumn(chunkData, localX, localZ, minY, maxY, surface, worldX, worldZ);
                this.applyHydrology(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyArena(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyRoads(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyLivingPoint(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyLandmark(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyNaturalFeatures(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyStructures(chunkData, localX, localZ, maxY, worldX, worldZ);
            }
        }
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return this.surfaceHeight(x, z) + 1;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    private void generateColumn(ChunkData chunkData, int localX, int localZ, int minY, int maxY, int surface, int worldX, int worldZ) {
        TerrainRegion region = TerrainLayout.region(this.floor, this.worldName, worldX, worldZ);
        chunkData.setBlock(localX, minY, localZ, Material.BEDROCK);
        for (int y = minY + 1; y <= surface; y++) {
            Material material = this.floor == 1
                    ? y == surface ? region.top() : y >= surface - 3 ? region.soil() : region.stone()
                    : y == surface ? this.theme.top() : y >= surface - 3 ? this.theme.soil() : this.theme.stone();
            if (this.floor >= 3 && y < surface - 8) {
                material = Material.DEEPSLATE;
            }
            chunkData.setBlock(localX, y, localZ, material);
        }
        if (this.floor == 1 && surface < 62) {
            for (int y = surface + 1; y <= 62 && y < maxY; y++) {
                chunkData.setBlock(localX, y, localZ, Material.WATER);
            }
        }
        if (this.floor >= 3 && Math.floorMod(worldX + worldZ, 41) == 0) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.AMETHYST_CLUSTER);
        }
    }

    private void applyVillage(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        for (TerrainPoint village : this.villages) {
            int dx = worldX - village.x();
            int dz = worldZ - village.z();
            int distance = Math.max(Math.abs(dx), Math.abs(dz));
            if (distance > 58) {
                continue;
            }
            int y = Math.min(maxY - 10, Math.max(surface, village.y()));
            if (Math.abs(dx) <= 3 || Math.abs(dz) <= 3 || (Math.abs(dx) <= 18 && Math.abs(dz - 18) <= 2)) {
                chunkData.setBlock(localX, y, localZ, this.theme.road());
                this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
            }
            this.applyBuilding(chunkData, localX, localZ, maxY, y, dx, dz, -26, -18, -13, -6);
            this.applyBuilding(chunkData, localX, localZ, maxY, y, dx, dz, 14, -22, 27, -10);
            this.applyBuilding(chunkData, localX, localZ, maxY, y, dx, dz, -28, 12, -14, 25);
            this.applyMarketStall(chunkData, localX, localZ, maxY, y, dx, dz);
            this.applyGarden(chunkData, localX, localZ, maxY, y, dx, dz);
            this.applyWell(chunkData, localX, localZ, maxY, y, dx, dz);
            this.applyNoticeBoard(chunkData, localX, localZ, maxY, y, dx, dz);
            this.applyTower(chunkData, localX, localZ, maxY, y, dx, dz);
        }
    }

    private void applyBuilding(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz, int x0, int z0, int x1, int z1) {
        if (dx < x0 || dx > x1 || dz < z0 || dz > z1) {
            return;
        }
        boolean edge = dx == x0 || dx == x1 || dz == z0 || dz == z1;
        boolean door = dz == z0 && Math.abs(dx - ((x0 + x1) / 2)) <= 1;
        chunkData.setBlock(localX, y, localZ, this.theme.wall());
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 4, maxY);
        if (edge && !door) {
            for (int yy = y + 1; yy <= y + 4; yy++) {
                chunkData.setBlock(localX, yy, localZ, this.theme.wall());
            }
        }
        if (door) {
            chunkData.setBlock(localX, y + 1, localZ, Material.AIR);
            chunkData.setBlock(localX, y + 2, localZ, Material.AIR);
        }
        chunkData.setBlock(localX, y + 5, localZ, this.theme.roof());
        if (edge) {
            chunkData.setBlock(localX, y + 6, localZ, this.theme.roof());
        }
    }

    private void applyTower(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz) {
        int distance = (int) Math.round(Math.hypot(dx - 24, dz - 24));
        if (distance > 5) {
            return;
        }
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 12, maxY);
        if (distance >= 4) {
            for (int yy = y + 1; yy <= y + 11; yy++) {
                chunkData.setBlock(localX, yy, localZ, yy % 4 == 0 ? this.theme.accent() : this.theme.wall());
            }
        }
        chunkData.setBlock(localX, y + 12, localZ, this.theme.roof());
    }

    private void applyMarketStall(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz) {
        if (dx < 7 || dx > 15 || dz < 12 || dz > 20) {
            return;
        }
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
        if (dx == 7 || dx == 15 || dz == 12 || dz == 20) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.wall());
        }
        if ((dx == 7 || dx == 15) && (dz == 12 || dz == 20)) {
            for (int yy = y + 1; yy <= y + 3; yy++) {
                chunkData.setBlock(localX, yy, localZ, this.theme.wall());
            }
        }
        chunkData.setBlock(localX, y + 4, localZ, this.theme.roof());
    }

    private void applyGarden(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz) {
        if (dx < -8 || dx > 8 || dz < 24 || dz > 34) {
            return;
        }
        chunkData.setBlock(localX, y, localZ, (Math.abs(dx) + Math.abs(dz)) % 3 == 0 ? Material.FARMLAND : this.theme.soil());
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 3, maxY);
        if (Math.floorMod(dx + dz, 4) == 0) {
            chunkData.setBlock(localX, y + 1, localZ, Material.WHEAT);
        } else if (Math.floorMod(dx - dz, 5) == 0) {
            chunkData.setBlock(localX, y + 1, localZ, Material.CARROTS);
        }
    }

    private void applyWell(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz) {
        if (Math.abs(dx + 18) > 3 || Math.abs(dz - 2) > 3) {
            return;
        }
        int radius = Math.max(Math.abs(dx + 18), Math.abs(dz - 2));
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
        if (radius == 3) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.wall());
        } else if (radius <= 1) {
            chunkData.setBlock(localX, y, localZ, Material.WATER);
        } else {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
        }
        if (radius == 3) {
            chunkData.setBlock(localX, y + 4, localZ, this.theme.roof());
        }
    }

    private void applyNoticeBoard(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz) {
        if (dx < -4 || dx > 4 || dz != -8) {
            return;
        }
        this.clearColumn(chunkData, localX, localZ, y + 1, y + 4, maxY);
        if (Math.abs(dx) == 4) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.wall());
            chunkData.setBlock(localX, y + 2, localZ, this.theme.wall());
        } else {
            chunkData.setBlock(localX, y + 2, localZ, this.theme.accent());
            chunkData.setBlock(localX, y + 3, localZ, this.theme.accent());
        }
    }

    private void applyArena(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        if (this.arena == null) {
            return;
        }
        int dx = worldX - this.arena.x();
        int dz = worldZ - this.arena.z();
        int distance = (int) Math.round(Math.hypot(dx, dz));
        if (distance > 78) {
            return;
        }
        int y = Math.min(maxY - 12, Math.max(surface, this.arena.y()));
        if (distance <= 38) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 9, maxY);
        }
        if (distance >= 39 && distance <= 43) {
            for (int yy = y + 1; yy <= y + 5; yy++) {
                chunkData.setBlock(localX, yy, localZ, this.theme.wall());
            }
        }
        if ((Math.abs(dx) <= 2 && Math.abs(dz) <= 30) || (Math.abs(dz) <= 2 && Math.abs(dx) <= 30)) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.accent());
        }
    }

    private void applyLandmark(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        for (TerrainPoint landmark : this.landmarks) {
            int dx = worldX - landmark.x();
            int dz = worldZ - landmark.z();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            if (distance > 8) {
                continue;
            }
            int y = Math.min(maxY - 18, Math.max(surface, landmark.y()));
            if (distance <= 2) {
                for (int yy = y + 1; yy <= y + 15; yy++) {
                    chunkData.setBlock(localX, yy, localZ, yy % 5 == 0 ? this.theme.accent() : this.theme.wall());
                }
            } else if (distance <= 5) {
                chunkData.setBlock(localX, y + 1, localZ, this.theme.road());
            }
        }
    }

    private void applyLivingPoint(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        for (TerrainPoint point : this.livingPoints) {
            int dx = worldX - point.x();
            int dz = worldZ - point.z();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            if (distance > 16) {
                continue;
            }
            int y = Math.min(maxY - 12, Math.max(surface, point.y()));
            switch (point.type()) {
                case "camp" -> this.applyCamp(chunkData, localX, localZ, maxY, y, dx, dz, distance);
                case "waystone" -> this.applyWaystone(chunkData, localX, localZ, maxY, y, dx, dz, distance);
                case "road_marker" -> this.applyRoadMarker(chunkData, localX, localZ, maxY, y, dx, dz, distance);
                case "shrine" -> this.applyShrine(chunkData, localX, localZ, maxY, y, dx, dz, distance);
                default -> {
                }
            }
        }
    }

    private void applyCamp(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz, int distance) {
        if (distance <= 10) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
        }
        if (distance == 3) {
            chunkData.setBlock(localX, y + 1, localZ, Material.CAMPFIRE);
        }
        if ((Math.abs(dx) == 8 && Math.abs(dz) <= 4) || (Math.abs(dz) == 8 && Math.abs(dx) <= 4)) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.wall());
        }
    }

    private void applyWaystone(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz, int distance) {
        if (distance <= 5) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 8, maxY);
        }
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            for (int yy = y + 1; yy <= y + 6; yy++) {
                chunkData.setBlock(localX, yy, localZ, yy == y + 4 ? this.theme.accent() : this.theme.wall());
            }
        }
    }

    private void applyRoadMarker(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz, int distance) {
        if (distance <= 6) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
        }
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            for (int yy = y + 1; yy <= y + 3; yy++) {
                chunkData.setBlock(localX, yy, localZ, this.theme.wall());
            }
            chunkData.setBlock(localX, y + 4, localZ, this.theme.accent());
        }
    }

    private void applyShrine(ChunkData chunkData, int localX, int localZ, int maxY, int y, int dx, int dz, int distance) {
        if (distance <= 9) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 7, maxY);
        }
        if (distance >= 5 && distance <= 6) {
            chunkData.setBlock(localX, y + 1, localZ, this.theme.wall());
        }
        if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
            for (int yy = y + 1; yy <= y + 4; yy++) {
                chunkData.setBlock(localX, yy, localZ, yy == y + 3 ? this.theme.accent() : this.theme.wall());
            }
        }
    }

    private void applyRoads(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        if (this.floor != 1 || this.villages.isEmpty()) {
            return;
        }
        int y = Math.min(maxY - 6, surface);
        TerrainPoint hub = this.villages.stream()
                .filter(point -> point.key().equalsIgnoreCase("first-haven") || point.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(this.villages.get(0));
        boolean road = false;
        for (TerrainPoint village : this.villages) {
            if (this.distanceToSegment(worldX, worldZ, hub.x(), hub.z(), village.x(), village.z()) <= 4.2D) {
                road = true;
            }
            if (Math.max(Math.abs(worldX - village.x()), Math.abs(worldZ - village.z())) <= 14) {
                road = true;
            }
        }
        for (TerrainPoint point : this.livingPoints) {
            if (this.distanceToSegment(worldX, worldZ, hub.x(), hub.z(), point.x(), point.z()) <= 3.4D) {
                road = true;
            }
        }
        if (this.arena != null && this.distanceToSegment(worldX, worldZ, hub.x(), hub.z(), this.arena.x(), this.arena.z()) <= 4.5D) {
            road = true;
        }
        if (road) {
            chunkData.setBlock(localX, y, localZ, this.theme.road());
            this.clearColumn(chunkData, localX, localZ, y + 1, y + 5, maxY);
            this.applyRoadEdgeSupport(chunkData, localX, localZ, maxY, y, worldX, worldZ);
            if (Math.floorMod(worldX * 7 + worldZ * 11, 23) == 0) {
                chunkData.setBlock(localX, y + 1, localZ, Material.LANTERN);
            }
        }
    }

    private void applyHydrology(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        if (this.floor != 1 || this.nearAuthoredArea(worldX, worldZ)) {
            return;
        }
        double stream = TerrainLayout.streamSignal(this.worldName, worldX, worldZ);
        TerrainRegion region = TerrainLayout.region(this.floor, this.worldName, worldX, worldZ);
        if (Math.abs(stream) <= 0.055D || region == TerrainRegion.RIVER_VALLEY && Math.abs(stream) <= 0.10D) {
            int waterY = Math.min(surface, 63);
            chunkData.setBlock(localX, waterY, localZ, Material.WATER);
            this.clearColumn(chunkData, localX, localZ, waterY + 1, waterY + 3, maxY);
            if (Math.abs(stream) > 0.045D) {
                chunkData.setBlock(localX, Math.max(chunkData.getMinHeight() + 1, waterY - 1), localZ, Material.MUD);
            }
        } else if (Math.abs(stream) <= 0.14D && surface <= 68) {
            chunkData.setBlock(localX, surface, localZ, Material.MUD);
        }
    }

    private void applyRoadEdgeSupport(ChunkData chunkData, int localX, int localZ, int maxY, int y, int worldX, int worldZ) {
        int east = TerrainLayout.surfaceHeight(this.floor, this.worldName, worldX + 3, worldZ);
        int west = TerrainLayout.surfaceHeight(this.floor, this.worldName, worldX - 3, worldZ);
        int north = TerrainLayout.surfaceHeight(this.floor, this.worldName, worldX, worldZ - 3);
        int south = TerrainLayout.surfaceHeight(this.floor, this.worldName, worldX, worldZ + 3);
        int slope = Math.max(Math.max(Math.abs(east - west), Math.abs(north - south)), 0);
        if (slope >= 5) {
            for (int yy = Math.max(chunkData.getMinHeight() + 1, y - 3); yy < y; yy++) {
                chunkData.setBlock(localX, yy, localZ, Material.STONE_BRICKS);
            }
            if (Math.floorMod(worldX + worldZ, 5) == 0 && y + 1 < maxY) {
                chunkData.setBlock(localX, y + 1, localZ, Material.OAK_FENCE);
            }
        } else if (slope >= 3 && Math.floorMod(worldX * 5 + worldZ, 7) == 0 && y + 1 < maxY) {
            chunkData.setBlock(localX, y + 1, localZ, Material.COBBLESTONE_STAIRS);
        }
    }

    private void applyNaturalFeatures(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        if (this.floor != 1 || this.nearAuthoredArea(worldX, worldZ)) {
            return;
        }
        TerrainRegion region = TerrainLayout.region(this.floor, this.worldName, worldX, worldZ);
        this.applyClusteredTree(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
        this.applyRockCluster(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
        this.applyPond(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
        this.applyFallenLog(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
        this.applyForestClutter(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
        this.applyHeroTree(chunkData, localX, localZ, maxY, surface, worldX, worldZ, region);
    }

    private void applyClusteredTree(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        int grid = region == TerrainRegion.STARTER_FOREST ? 15 : 24;
        int cellX = Math.floorDiv(worldX, grid);
        int cellZ = Math.floorDiv(worldZ, grid);
        for (int gx = cellX - 1; gx <= cellX + 1; gx++) {
            for (int gz = cellZ - 1; gz <= cellZ + 1; gz++) {
                int chance = this.hash(gx, gz, region.ordinal() + 700);
                int threshold = switch (region) {
                    case STARTER_FOREST -> 6200;
                    case OAK_HIGHLANDS -> 3600;
                    case MEADOW_BASIN, FARMLAND_FLATS -> 900;
                    default -> 1500;
                };
                if (chance > threshold) {
                    continue;
                }
                int centerX = gx * grid + 4 + Math.floorMod(chance, Math.max(5, grid - 8));
                int centerZ = gz * grid + 4 + Math.floorMod(chance / 17, Math.max(5, grid - 8));
                int dx = worldX - centerX;
                int dz = worldZ - centerZ;
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist > 4 || surface < 63 || surface > maxY - 12) {
                    continue;
                }
                if (dx == 0 && dz == 0) {
                    for (int y = surface + 1; y <= surface + 5; y++) {
                        chunkData.setBlock(localX, y, localZ, Material.OAK_LOG);
                    }
                }
                if (dist <= 3 && surface + 5 < maxY) {
                    int leafY = surface + 4 + (dist <= 1 ? 1 : 0);
                    chunkData.setBlock(localX, leafY, localZ, Material.OAK_LEAVES);
                    if (dist <= 2) {
                        chunkData.setBlock(localX, leafY + 1, localZ, Material.OAK_LEAVES);
                    }
                }
            }
        }
    }

    private void applyRockCluster(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        if (region == TerrainRegion.FARMLAND_FLATS || region == TerrainRegion.MEADOW_BASIN) {
            return;
        }
        int grid = 31;
        int gx = Math.floorDiv(worldX, grid);
        int gz = Math.floorDiv(worldZ, grid);
        int chance = this.hash(gx, gz, 901);
        if (chance > 1400) {
            return;
        }
        int centerX = gx * grid + 6 + Math.floorMod(chance, grid - 12);
        int centerZ = gz * grid + 6 + Math.floorMod(chance / 13, grid - 12);
        int distance = (int) Math.round(Math.hypot(worldX - centerX, worldZ - centerZ));
        if (distance <= 2 && surface + 2 < maxY) {
            chunkData.setBlock(localX, surface + 1, localZ, distance == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            if (distance == 0) {
                chunkData.setBlock(localX, surface + 2, localZ, Material.MOSSY_COBBLESTONE);
            }
        }
    }

    private void applyPond(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        if (region != TerrainRegion.RIVER_VALLEY && region != TerrainRegion.MEADOW_BASIN) {
            return;
        }
        int grid = 58;
        int gx = Math.floorDiv(worldX, grid);
        int gz = Math.floorDiv(worldZ, grid);
        int chance = this.hash(gx, gz, 1103);
        if (chance > 900) {
            return;
        }
        int centerX = gx * grid + 12 + Math.floorMod(chance, grid - 24);
        int centerZ = gz * grid + 12 + Math.floorMod(chance / 19, grid - 24);
        int distance = (int) Math.round(Math.hypot(worldX - centerX, worldZ - centerZ));
        if (distance <= 5) {
            chunkData.setBlock(localX, surface, localZ, distance <= 4 ? Material.WATER : Material.MUD);
            this.clearColumn(chunkData, localX, localZ, surface + 1, surface + 3, maxY);
        }
    }

    private void applyFallenLog(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        if (region != TerrainRegion.STARTER_FOREST && region != TerrainRegion.OAK_HIGHLANDS) {
            return;
        }
        int grid = 43;
        int gx = Math.floorDiv(worldX, grid);
        int gz = Math.floorDiv(worldZ, grid);
        int chance = this.hash(gx, gz, 1207);
        if (chance > 900) {
            return;
        }
        int centerX = gx * grid + 8 + Math.floorMod(chance, grid - 16);
        int centerZ = gz * grid + 8 + Math.floorMod(chance / 23, grid - 16);
        if (Math.abs(worldZ - centerZ) <= 1 && Math.abs(worldX - centerX) <= 5 && surface + 1 < maxY) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.OAK_LOG);
        }
    }

    private void applyForestClutter(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        if (surface + 1 >= maxY || region == TerrainRegion.GATE_WILDS || region == TerrainRegion.FARMLAND_FLATS) {
            return;
        }
        int roll = this.hash(worldX, worldZ, 1409);
        if (region == TerrainRegion.STARTER_FOREST && roll < 220) {
            chunkData.setBlock(localX, surface + 1, localZ, roll % 2 == 0 ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM);
        } else if (roll < 130) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.FERN);
        } else if (roll >= 9900 && surface + 2 < maxY) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.OAK_LOG);
            chunkData.setBlock(localX, surface + 2, localZ, Material.OAK_LEAVES);
        }
    }

    private void applyHeroTree(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ, TerrainRegion region) {
        if (region != TerrainRegion.STARTER_FOREST && region != TerrainRegion.SHRINE_RIDGE) {
            return;
        }
        int grid = 180;
        int cellX = Math.floorDiv(worldX, grid);
        int cellZ = Math.floorDiv(worldZ, grid);
        int chance = this.hash(cellX, cellZ, 1701);
        if (chance > 850) {
            return;
        }
        int centerX = cellX * grid + 50 + Math.floorMod(chance, grid - 100);
        int centerZ = cellZ * grid + 50 + Math.floorMod(chance / 29, grid - 100);
        int dx = worldX - centerX;
        int dz = worldZ - centerZ;
        int distance = (int) Math.round(Math.hypot(dx, dz));
        if (distance > 10 || surface + 16 >= maxY) {
            return;
        }
        if (distance <= 2) {
            for (int y = surface + 1; y <= surface + 12; y++) {
                chunkData.setBlock(localX, y, localZ, y % 4 == 0 ? Material.STRIPPED_OAK_LOG : Material.OAK_LOG);
            }
        }
        if (distance >= 4 && distance <= 9 && Math.floorMod(dx + dz, 3) == 0) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.OAK_LOG);
        }
        if (distance <= 10) {
            int leafY = surface + 10 + (distance <= 5 ? 2 : 0);
            chunkData.setBlock(localX, leafY, localZ, Material.OAK_LEAVES);
            if (distance <= 7 && leafY + 1 < maxY) {
                chunkData.setBlock(localX, leafY + 1, localZ, Material.AZALEA_LEAVES);
            }
        }
    }

    private void applyStructures(ChunkData chunkData, int localX, int localZ, int maxY, int worldX, int worldZ) {
        for (StructurePlacement placement : this.structurePlacements) {
            placement.applyToColumn(chunkData, localX, localZ, maxY, worldX, worldZ);
        }
    }

    private void clearColumn(ChunkData chunkData, int localX, int localZ, int y0, int y1, int maxY) {
        for (int y = y0; y <= y1 && y < maxY; y++) {
            chunkData.setBlock(localX, y, localZ, Material.AIR);
        }
    }

    private boolean nearAuthoredArea(int worldX, int worldZ) {
        for (TerrainPoint village : this.villages) {
            if (Math.hypot(worldX - village.x(), worldZ - village.z()) <= 86.0D) {
                return true;
            }
        }
        for (TerrainPoint point : this.livingPoints) {
            if (Math.hypot(worldX - point.x(), worldZ - point.z()) <= 26.0D) {
                return true;
            }
        }
        if (this.arena != null && Math.hypot(worldX - this.arena.x(), worldZ - this.arena.z()) <= 92.0D) {
            return true;
        }
        return false;
    }

    private double distanceToSegment(int x, int z, int x1, int z1, int x2, int z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.001D) {
            return Math.hypot(x - x1, z - z1);
        }
        double t = ((x - x1) * dx + (z - z1) * dz) / lengthSquared;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return Math.hypot(x - (x1 + t * dx), z - (z1 + t * dz));
    }

    private int hash(int x, int z, int salt) {
        long value = TerrainLayout.layoutSeed(this.worldName, this.floor) ^ (x * 73428767L) ^ (z * 91227153L) ^ (salt * 132897987541L);
        value ^= value >>> 13;
        value *= 1274126177L;
        value ^= value >>> 16;
        return (int) Math.floorMod(value, 10000L);
    }

    private int surfaceHeight(int worldX, int worldZ) {
        int height = TerrainLayout.surfaceHeight(this.floor, this.worldName, worldX, worldZ);
        for (TerrainPoint village : this.villages) {
            int distance = Math.max(Math.abs(worldX - village.x()), Math.abs(worldZ - village.z()));
            int radius = this.floor == 1 ? 76 : 58;
            if (distance <= radius) {
                height = this.blend(height, village.y(), radius - distance, radius);
            }
        }
        if (this.arena != null) {
            int distance = (int) Math.round(Math.hypot(worldX - this.arena.x(), worldZ - this.arena.z()));
            if (distance <= 80) {
                height = this.blend(height, this.arena.y(), 80 - distance, 80);
            }
        }
        return height;
    }

    private int blend(int current, int target, int weight, int max) {
        double amount = Math.max(0.0D, Math.min(1.0D, weight / (double) max));
        return (int) Math.round(current * (1.0D - amount) + target * amount);
    }
}
