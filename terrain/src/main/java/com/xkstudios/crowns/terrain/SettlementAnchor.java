package com.xkstudios.crowns.terrain;

public record SettlementAnchor(
        String key,
        TerrainDistrict district,
        int x,
        int y,
        int z,
        String templateKey,
        int rotation
) {
}
