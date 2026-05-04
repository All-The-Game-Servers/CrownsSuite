package com.xkstudios.crowns.terrain;

import java.util.List;

public final class TerrainDesignLanguage {
    private TerrainDesignLanguage() {
    }

    public static List<String> floorOneRegions() {
        return List.of(
                "meadow basin",
                "oak highlands",
                "river valley",
                "starter forest",
                "farmland flats",
                "shrine ridge",
                "gate wilds"
        );
    }

    public static List<String> floorOneDistricts() {
        return List.of(
                TerrainDistrict.CIVIC.displayName(),
                TerrainDistrict.RESIDENTIAL.displayName(),
                TerrainDistrict.MARKET.displayName(),
                TerrainDistrict.FARMING.displayName(),
                TerrainDistrict.ROAD_EDGE.displayName(),
                TerrainDistrict.DEFENSIVE_EDGE.displayName(),
                TerrainDistrict.WILDERNESS_EDGE.displayName()
        );
    }

    public static String treePoolSummary() {
        return "canopy, edge, understory, roots/stumps, fallen logs, rare hero trees";
    }

    public static String hydrologySummary() {
        return "river valleys, stream cuts, ponds, mud banks, wetland edges, bridge-ready crossings";
    }
}
