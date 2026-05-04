package com.xkstudios.crowns.terrain;

public enum TerrainDistrict {
    CIVIC("Civic Core"),
    RESIDENTIAL("Residential Rise"),
    MARKET("Market Road"),
    FARMING("Farm Terraces"),
    ROAD_EDGE("Road Edge"),
    DEFENSIVE_EDGE("Defensive Edge"),
    WILDERNESS_EDGE("Wilderness Edge");

    private final String displayName;

    TerrainDistrict(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
