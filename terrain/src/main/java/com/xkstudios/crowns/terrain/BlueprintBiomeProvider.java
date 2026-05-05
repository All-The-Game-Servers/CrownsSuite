package com.xkstudios.crowns.terrain;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

public final class BlueprintBiomeProvider extends BiomeProvider {
    private static final List<Biome> BIOMES = List.of(
            Biome.PLAINS,
            Biome.MEADOW,
            Biome.FOREST,
            Biome.OLD_GROWTH_PINE_TAIGA,
            Biome.RIVER,
            Biome.STONY_PEAKS,
            Biome.WINDSWEPT_HILLS,
            Biome.SUNFLOWER_PLAINS
    );

    private final FloorBlueprint blueprint;

    public BlueprintBiomeProvider(FloorBlueprint blueprint) {
        this.blueprint = blueprint;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        if (this.blueprint.river(x, z) || this.blueprint.riverDistance(x, z) <= 14.0D) {
            return Biome.RIVER;
        }
        return switch (this.blueprint.biomeKey(x, z)) {
            case "old_growth" -> Biome.OLD_GROWTH_PINE_TAIGA;
            case "frontier_fields" -> Biome.SUNFLOWER_PLAINS;
            case "riverlands" -> Biome.RIVER;
            case "shrine_ridge" -> Biome.MEADOW;
            case "gate_wilds", "broken_highlands" -> Biome.WINDSWEPT_HILLS;
            case "road_edge" -> Biome.PLAINS;
            default -> Biome.MEADOW;
        };
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return BIOMES;
    }
}
