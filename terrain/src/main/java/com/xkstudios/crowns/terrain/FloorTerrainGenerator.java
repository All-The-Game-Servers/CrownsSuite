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

    public FloorTerrainGenerator(int floor, String worldName, TerrainTheme theme, List<TerrainPoint> villages, TerrainPoint arena, List<TerrainPoint> landmarks, List<TerrainPoint> livingPoints) {
        this.floor = floor;
        this.worldName = worldName;
        this.theme = theme;
        this.villages = List.copyOf(villages);
        this.arena = arena;
        this.landmarks = List.copyOf(landmarks);
        this.livingPoints = List.copyOf(livingPoints);
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
                this.applyArena(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyRoads(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyVillage(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyLivingPoint(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
                this.applyLandmark(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
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
        chunkData.setBlock(localX, minY, localZ, Material.BEDROCK);
        for (int y = minY + 1; y <= surface; y++) {
            Material material = y == surface ? this.theme.top() : y >= surface - 3 ? this.theme.soil() : this.theme.stone();
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
        if (this.floor == 1 && surface >= 64 && Math.floorMod(worldX * 17 + worldZ * 31, 97) == 0) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.OAK_SAPLING);
        }
        if (this.floor >= 2 && Math.floorMod(worldX * 13 + worldZ * 29 + this.floor, 173) == 0) {
            chunkData.setBlock(localX, surface + 1, localZ, this.floor == 2 ? Material.SPRUCE_SAPLING : Material.AZALEA);
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
        for (TerrainPoint village : this.villages) {
            if (Math.abs(worldX - village.x()) <= 3 || Math.abs(worldZ - village.z()) <= 3) {
                int distance = Math.min(Math.abs(worldX - village.x()), Math.abs(worldZ - village.z()));
                if (distance <= 4) {
                    chunkData.setBlock(localX, y, localZ, this.theme.road());
                    this.clearColumn(chunkData, localX, localZ, y + 1, y + 4, maxY);
                }
            }
        }
    }

    private void clearColumn(ChunkData chunkData, int localX, int localZ, int y0, int y1, int maxY) {
        for (int y = y0; y <= y1 && y < maxY; y++) {
            chunkData.setBlock(localX, y, localZ, Material.AIR);
        }
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
