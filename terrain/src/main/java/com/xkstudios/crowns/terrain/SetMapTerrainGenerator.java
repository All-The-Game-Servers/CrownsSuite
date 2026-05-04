package com.xkstudios.crowns.terrain;

import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

public class SetMapTerrainGenerator extends ChunkGenerator {
    private final int floor;
    private final String worldName;

    public SetMapTerrainGenerator(int floor, String worldName) {
        this.floor = floor;
        this.worldName = worldName;
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
                chunkData.setBlock(localX, minY, localZ, Material.BEDROCK);
                for (int y = minY + 1; y <= surface; y++) {
                    Material material = y == surface ? this.topMaterial(worldX, worldZ)
                            : y >= surface - 3 ? Material.DIRT
                            : y < 20 ? Material.DEEPSLATE
                            : Material.STONE;
                    chunkData.setBlock(localX, y, localZ, material);
                }
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

    private int surfaceHeight(int x, int z) {
        if (this.floor != 1) {
            return 68;
        }
        double distance = Math.hypot(x, z);
        double basin = Math.max(0.0D, 1.0D - Math.min(1.0D, distance / 620.0D));
        double hills = Math.sin((x + this.worldName.length() * 31) / 180.0D) * 2.0D
                + Math.cos((z - this.worldName.length() * 17) / 210.0D) * 2.0D
                + Math.sin((x - z) / 260.0D) * 1.5D;
        int height = 69 + (int) Math.round(hills) - (int) Math.round(basin * 2.0D);
        if (x > 760 && z > 560) {
            height += 5 + (int) Math.round(Math.sin((x + z) / 120.0D) * 2.0D);
        }
        if (z < -420) {
            height += 4;
        }
        return Math.max(62, Math.min(92, height));
    }

    private Material topMaterial(int x, int z) {
        if (x > 760 && z > 560) {
            return Math.floorMod(x + z, 7) == 0 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
        }
        if (z < -420) {
            return Math.floorMod(x * 3 + z, 11) == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
        }
        return Material.GRASS_BLOCK;
    }
}
