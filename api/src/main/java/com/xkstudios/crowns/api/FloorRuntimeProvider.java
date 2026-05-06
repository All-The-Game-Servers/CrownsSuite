package com.xkstudios.crowns.api;

import java.util.List;

public interface FloorRuntimeProvider {
    FloorRuntimeSnapshot getFloorRuntime(int floorNumber);

    default boolean isFloorSafeReady(int floorNumber) {
        FloorRuntimeSnapshot snapshot = this.getFloorRuntime(floorNumber);
        return snapshot != null && snapshot.safeReady();
    }

    default TerrainPoint getFloorAnchor(int floorNumber, String key) {
        FloorRuntimeSnapshot snapshot = this.getFloorRuntime(floorNumber);
        return snapshot == null ? null : snapshot.anchor(key);
    }

    default List<String> getFloorRepairSteps(int floorNumber) {
        FloorRuntimeSnapshot snapshot = this.getFloorRuntime(floorNumber);
        return snapshot == null ? List.of() : snapshot.repairSteps();
    }
}
