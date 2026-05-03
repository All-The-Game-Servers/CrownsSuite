package com.xkstudios.crowns.terrain;

import org.bukkit.Material;

public enum TerrainRegion {
    MEADOW_BASIN("Meadow Basin", Material.GRASS_BLOCK, Material.DIRT, Material.STONE, 66),
    OAK_HIGHLANDS("Oak Highlands", Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.STONE, 76),
    RIVER_VALLEY("River Valley", Material.GRASS_BLOCK, Material.DIRT, Material.STONE, 60),
    STARTER_FOREST("Starter Forest", Material.PODZOL, Material.ROOTED_DIRT, Material.STONE, 70),
    FARMLAND_FLATS("Farmland Flats", Material.GRASS_BLOCK, Material.DIRT, Material.STONE, 64),
    SHRINE_RIDGE("Shrine Ridge", Material.MOSS_BLOCK, Material.DIRT, Material.ANDESITE, 84),
    GATE_WILDS("Gate Wilds", Material.COARSE_DIRT, Material.DIRT, Material.STONE, 78);

    private final String displayName;
    private final Material top;
    private final Material soil;
    private final Material stone;
    private final int baseHeight;

    TerrainRegion(String displayName, Material top, Material soil, Material stone, int baseHeight) {
        this.displayName = displayName;
        this.top = top;
        this.soil = soil;
        this.stone = stone;
        this.baseHeight = baseHeight;
    }

    public String displayName() {
        return this.displayName;
    }

    public Material top() {
        return this.top;
    }

    public Material soil() {
        return this.soil;
    }

    public Material stone() {
        return this.stone;
    }

    public int baseHeight() {
        return this.baseHeight;
    }
}
