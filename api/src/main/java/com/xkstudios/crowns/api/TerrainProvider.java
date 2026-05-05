package com.xkstudios.crowns.api;

import java.util.List;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

public interface TerrainProvider {
    ChunkGenerator getGeneratorForFloor(int floorNumber, String worldName, World.Environment environment, long resourceTier);

    TerrainPoint getBossArena(int floorNumber, String worldName);

    List<TerrainPoint> getVillages(int floorNumber, String worldName);

    List<TerrainPoint> getLandmarks(int floorNumber, String worldName);

    default List<TerrainPoint> getPoints(int floorNumber, String worldName, String type) {
        if ("village".equalsIgnoreCase(type)) {
            return this.getVillages(floorNumber, worldName);
        }
        if ("landmark".equalsIgnoreCase(type)) {
            return this.getLandmarks(floorNumber, worldName);
        }
        TerrainPoint arena = "arena".equalsIgnoreCase(type) ? this.getBossArena(floorNumber, worldName) : null;
        return arena == null ? List.of() : List.of(arena);
    }

    default List<TerrainPoint> getAllPoints(int floorNumber, String worldName) {
        java.util.ArrayList<TerrainPoint> points = new java.util.ArrayList<>();
        points.addAll(this.getVillages(floorNumber, worldName));
        points.addAll(this.getLandmarks(floorNumber, worldName));
        TerrainPoint arena = this.getBossArena(floorNumber, worldName);
        if (arena != null) {
            points.add(arena);
        }
        return points;
    }

    String getFloorTheme(int floorNumber);

    default boolean isFloorReadyForPlayers(int floorNumber) {
        return true;
    }

    default String getFloorReadinessSummary(int floorNumber) {
        return "Floor " + floorNumber + " readiness is not managed by this terrain provider.";
    }
}
