package com.xkstudios.crowns.terrain;

import org.bukkit.Material;

public record TerrainTheme(
        int floor,
        String name,
        Material top,
        Material soil,
        Material stone,
        Material road,
        Material wall,
        Material roof,
        Material accent,
        int baseHeight
) {
    public static TerrainTheme forFloor(int floor, String configuredName) {
        return switch (floor) {
            case 1 -> new TerrainTheme(floor, configuredName == null ? "First Haven" : configuredName,
                    Material.GRASS_BLOCK, Material.DIRT, Material.STONE, Material.COBBLESTONE,
                    Material.OAK_PLANKS, Material.DARK_OAK_STAIRS, Material.COPPER_BLOCK, 67);
            case 2 -> new TerrainTheme(floor, configuredName == null ? "Ironwood Rise" : configuredName,
                    Material.PODZOL, Material.ROOTED_DIRT, Material.STONE, Material.POLISHED_ANDESITE,
                    Material.SPRUCE_PLANKS, Material.DEEPSLATE_TILE_STAIRS, Material.IRON_BLOCK, 76);
            case 3 -> new TerrainTheme(floor, configuredName == null ? "Veilglass Expanse" : configuredName,
                    Material.SCULK, Material.DEEPSLATE, Material.TUFF, Material.POLISHED_DEEPSLATE,
                    Material.DARK_OAK_PLANKS, Material.PURPUR_STAIRS, Material.AMETHYST_BLOCK, 82);
            default -> new TerrainTheme(floor, configuredName == null ? "Floor " + floor : configuredName,
                    Material.GRASS_BLOCK, Material.DIRT, Material.STONE, Material.COBBLESTONE,
                    Material.SPRUCE_PLANKS, Material.DEEPSLATE_TILE_STAIRS, Material.GOLD_BLOCK,
                    Math.min(96, 66 + floor * 4));
        };
    }
}
