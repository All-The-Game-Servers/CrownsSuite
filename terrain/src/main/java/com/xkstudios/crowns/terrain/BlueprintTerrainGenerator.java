package com.xkstudios.crowns.terrain;

import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

public final class BlueprintTerrainGenerator extends ChunkGenerator {
    private final FloorBlueprint blueprint;
    private final boolean vanillaCaves;
    private final boolean vanillaMobs;
    private final boolean vanillaDecorations;
    private final boolean vanillaStructures;

    public BlueprintTerrainGenerator(FloorBlueprint blueprint, boolean vanillaCaves, boolean vanillaMobs, boolean vanillaDecorations, boolean vanillaStructures) {
        this.blueprint = blueprint;
        this.vanillaCaves = vanillaCaves;
        this.vanillaMobs = vanillaMobs;
        this.vanillaDecorations = vanillaDecorations;
        this.vanillaStructures = vanillaStructures;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int surface = Math.min(maxY - 16, this.blueprint.surfaceHeight(worldX, worldZ));
                this.generateColumn(chunkData, localX, localZ, minY, maxY, surface, worldX, worldZ);
            }
        }
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return this.blueprint.surfaceHeight(x, z) + 1;
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
        return this.vanillaCaves;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return this.vanillaDecorations;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return this.vanillaStructures;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return this.vanillaMobs;
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    private void generateColumn(ChunkData chunkData, int localX, int localZ, int minY, int maxY, int surface, int worldX, int worldZ) {
        chunkData.setBlock(localX, minY, localZ, Material.BEDROCK);
        Material top = this.topMaterial(worldX, worldZ);
        Material soil = this.soilMaterial(worldX, worldZ);
        for (int y = minY + 1; y <= surface; y++) {
            Material material = y == surface ? top : y >= surface - 4 ? soil : y < 20 ? Material.DEEPSLATE : Material.STONE;
            chunkData.setBlock(localX, y, localZ, material);
        }
        if (this.blueprint.river(worldX, worldZ)) {
            int waterY = Math.max(62, Math.min(surface, this.blueprint.roadY(worldX, worldZ) - 2));
            chunkData.setBlock(localX, waterY, localZ, Material.WATER);
            for (int y = waterY + 1; y <= Math.min(maxY - 1, waterY + 3); y++) {
                chunkData.setBlock(localX, y, localZ, Material.AIR);
            }
        }
        this.applyBlueprintOverlays(chunkData, localX, localZ, maxY, surface, worldX, worldZ);
    }

    private void applyBlueprintOverlays(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int worldX, int worldZ) {
        double roadDistance = this.blueprint.distanceToRoad(worldX, worldZ);
        if (roadDistance <= 7.0D) {
            int roadY = Math.min(maxY - 8, this.blueprint.roadY(worldX, worldZ));
            chunkData.setBlock(localX, roadY, localZ, roadDistance <= 5.0D ? Material.COBBLESTONE : Material.ANDESITE);
            for (int y = roadY + 1; y <= roadY + 4 && y < maxY; y++) {
                chunkData.setBlock(localX, y, localZ, Material.AIR);
            }
        }
        for (FloorBlueprint.Decoration decoration : this.blueprint.decorations()) {
            int dx = worldX - decoration.x();
            int dz = worldZ - decoration.z();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            if (distance > decoration.radius()) {
                continue;
            }
            if (decoration.type().equals("tree")) {
                this.applyTree(chunkData, localX, localZ, maxY, surface, dx, dz, distance);
            } else if (decoration.type().equals("rock")) {
                this.applyRock(chunkData, localX, localZ, maxY, surface, distance);
            } else if (decoration.type().equals("ruin")) {
                this.applyRuin(chunkData, localX, localZ, maxY, surface, dx, dz);
            }
        }
    }

    private void applyTree(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int dx, int dz, int distance) {
        if (surface + 12 >= maxY) {
            return;
        }
        if (dx == 0 && dz == 0) {
            for (int y = surface + 1; y <= surface + 8; y++) {
                chunkData.setBlock(localX, y, localZ, y % 4 == 0 ? Material.STRIPPED_OAK_LOG : Material.OAK_LOG);
            }
        }
        if (distance >= 3 && distance <= 7 && surface + 1 < maxY && Math.floorMod(dx + dz, 3) == 0) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.OAK_LOG);
        }
        if (distance <= 7) {
            int leafY = surface + 7 + (distance <= 3 ? 2 : 0);
            if (leafY < maxY) {
                chunkData.setBlock(localX, leafY, localZ, distance >= 6 ? Material.AZALEA_LEAVES : Material.OAK_LEAVES);
            }
        }
    }

    private void applyRock(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int distance) {
        if (distance <= 2 && surface + 2 < maxY) {
            chunkData.setBlock(localX, surface + 1, localZ, distance == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            if (distance == 0) {
                chunkData.setBlock(localX, surface + 2, localZ, Material.MOSSY_COBBLESTONE);
            }
        }
    }

    private void applyRuin(ChunkData chunkData, int localX, int localZ, int maxY, int surface, int dx, int dz) {
        if ((Math.abs(dx) == 4 || Math.abs(dz) == 4) && surface + 3 < maxY) {
            chunkData.setBlock(localX, surface + 1, localZ, Material.MOSSY_STONE_BRICKS);
            if (Math.floorMod(dx + dz, 2) == 0) {
                chunkData.setBlock(localX, surface + 2, localZ, Material.CRACKED_STONE_BRICKS);
            }
        }
    }

    private Material topMaterial(int x, int z) {
        String biome = this.blueprint.biomeKey(x, z);
        return switch (biome) {
            case "old_growth" -> Material.PODZOL;
            case "riverlands" -> this.blueprint.riverDistance(x, z) <= 12.0D ? Material.MUD : Material.GRASS_BLOCK;
            case "shrine_ridge" -> Material.MOSS_BLOCK;
            case "gate_wilds", "broken_highlands" -> Math.floorMod(x + z, 5) == 0 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            case "frontier_fields" -> Material.GRASS_BLOCK;
            case "road_edge" -> Material.ROOTED_DIRT;
            default -> Material.GRASS_BLOCK;
        };
    }

    private Material soilMaterial(int x, int z) {
        String biome = this.blueprint.biomeKey(x, z);
        return switch (biome) {
            case "riverlands" -> Material.MUD;
            case "old_growth" -> Material.ROOTED_DIRT;
            default -> Material.DIRT;
        };
    }
}
