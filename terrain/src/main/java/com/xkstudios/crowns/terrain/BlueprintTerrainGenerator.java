package com.xkstudios.crowns.terrain;

import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
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
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BlueprintBiomeProvider(this.blueprint);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new BlueprintBlockPopulator(this.blueprint));
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
