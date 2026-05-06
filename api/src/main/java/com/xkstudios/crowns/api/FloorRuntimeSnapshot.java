package com.xkstudios.crowns.api;

import java.util.List;

public record FloorRuntimeSnapshot(
        int floor,
        String worldName,
        String profileVersion,
        String state,
        boolean playerReady,
        boolean safeReady,
        String summary,
        List<String> repairSteps,
        List<TerrainPoint> anchors,
        List<String> qaLines
) {
    public FloorRuntimeSnapshot {
        repairSteps = repairSteps == null ? List.of() : List.copyOf(repairSteps);
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        qaLines = qaLines == null ? List.of() : List.copyOf(qaLines);
    }

    public TerrainPoint anchor(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.toLowerCase().replace('-', '_');
        for (TerrainPoint anchor : this.anchors) {
            String anchorKey = anchor.key().toLowerCase().replace('-', '_');
            if (anchorKey.equals(normalized) || anchorKey.replace('_', '-').equals(key.toLowerCase())) {
                return anchor;
            }
        }
        return null;
    }
}
