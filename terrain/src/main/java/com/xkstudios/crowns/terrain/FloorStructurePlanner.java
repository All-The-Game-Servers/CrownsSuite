package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

public final class FloorStructurePlanner {
    private FloorStructurePlanner() {
    }

    public static List<StructurePlacement> plan(
            int floor,
            TerrainTheme theme,
            StructureTemplateManager templates,
            List<TerrainPoint> villages,
            TerrainPoint arena,
            List<TerrainPoint> landmarks,
            List<TerrainPoint> livingPoints
    ) {
        List<StructurePlacement> placements = new ArrayList<>();
        if (floor != 1) {
            addSimpleAdventureFloor(placements, templates, theme, villages, arena, landmarks);
            return placements;
        }
        TerrainPoint firstHaven = villages.stream()
                .filter(point -> point.key().equalsIgnoreCase("first-haven") || point.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(villages.isEmpty() ? null : villages.get(0));
        if (firstHaven != null) {
            add(placements, templates, "spawn_plaza", firstHaven.x(), firstHaven.y(), firstHaven.z(), 0, theme.road());
            add(placements, templates, "notice_board", firstHaven.x() - 10, firstHaven.y(), firstHaven.z() - 16, 0, theme.road());
            add(placements, templates, "waystone", firstHaven.x() + 15, firstHaven.y(), firstHaven.z() - 2, 0, theme.road());
            add(placements, templates, "starter_shrine", firstHaven.x() - 24, firstHaven.y(), firstHaven.z() + 24, 1, theme.road());
            add(placements, templates, "haven_house", firstHaven.x() - 34, firstHaven.y(), firstHaven.z() - 24, 0, Material.STONE_BRICKS);
            add(placements, templates, "haven_house", firstHaven.x() + 34, firstHaven.y(), firstHaven.z() - 24, 2, Material.STONE_BRICKS);
            add(placements, templates, "haven_house", firstHaven.x() - 34, firstHaven.y(), firstHaven.z() + 22, 1, Material.STONE_BRICKS);
            add(placements, templates, "market_stall", firstHaven.x() + 28, firstHaven.y(), firstHaven.z() + 24, 3, theme.road());
            add(placements, templates, "watchtower", firstHaven.x() + 48, firstHaven.y(), firstHaven.z() + 44, 0, Material.STONE_BRICKS);
            add(placements, templates, "farm_plot", firstHaven.x() - 4, firstHaven.y(), firstHaven.z() + 46, 0, Material.DIRT);
            add(placements, templates, "farm_plot", firstHaven.x() + 18, firstHaven.y(), firstHaven.z() + 46, 0, Material.DIRT);
        }
        for (TerrainPoint village : villages) {
            if (village == firstHaven) {
                continue;
            }
            if (village.key().contains("farm")) {
                add(placements, templates, "farm_plot", village.x() - 12, village.y(), village.z(), 0, Material.DIRT);
                add(placements, templates, "farm_plot", village.x() + 12, village.y(), village.z(), 0, Material.DIRT);
                add(placements, templates, "haven_house", village.x(), village.y(), village.z() - 18, 0, Material.STONE_BRICKS);
            } else {
                add(placements, templates, "watchtower", village.x(), village.y(), village.z(), 0, Material.STONE_BRICKS);
                add(placements, templates, "haven_house", village.x() - 18, village.y(), village.z() + 18, 1, Material.STONE_BRICKS);
            }
        }
        for (TerrainPoint point : livingPoints) {
            switch (point.type()) {
                case "camp" -> add(placements, templates, "camp", point.x(), point.y(), point.z(), 0, theme.road());
                case "waystone" -> add(placements, templates, "waystone", point.x(), point.y(), point.z(), 0, theme.road());
                case "road_marker" -> add(placements, templates, "road_marker", point.x(), point.y(), point.z(), 0, theme.road());
                case "shrine" -> add(placements, templates, "starter_shrine", point.x(), point.y(), point.z(), 0, theme.road());
                default -> {
                }
            }
        }
        for (TerrainPoint landmark : landmarks) {
            add(placements, templates, "landmark_spire", landmark.x(), landmark.y(), landmark.z(), 0, Material.STONE_BRICKS);
        }
        if (arena != null) {
            add(placements, templates, "gate_arena_core", arena.x(), arena.y(), arena.z(), 0, Material.STONE_BRICKS);
            add(placements, templates, "gate_arch", arena.x(), arena.y(), arena.z() - 34, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruin_pillar", arena.x() - 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruin_pillar", arena.x() + 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruin_pillar", arena.x() - 42, arena.y(), arena.z() + 42, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruin_pillar", arena.x() + 42, arena.y(), arena.z() + 42, 0, Material.STONE_BRICKS);
        }
        return List.copyOf(placements);
    }

    private static void addSimpleAdventureFloor(List<StructurePlacement> placements, StructureTemplateManager templates, TerrainTheme theme, List<TerrainPoint> villages, TerrainPoint arena, List<TerrainPoint> landmarks) {
        for (TerrainPoint village : villages) {
            add(placements, templates, "watchtower", village.x(), village.y(), village.z(), 0, Material.STONE_BRICKS);
            add(placements, templates, "camp", village.x() + 24, village.y(), village.z() + 24, 0, theme.road());
        }
        for (TerrainPoint landmark : landmarks) {
            add(placements, templates, "landmark_spire", landmark.x(), landmark.y(), landmark.z(), 0, Material.STONE_BRICKS);
        }
        if (arena != null) {
            add(placements, templates, "gate_arena_core", arena.x(), arena.y(), arena.z(), 0, Material.STONE_BRICKS);
            add(placements, templates, "gate_arch", arena.x(), arena.y(), arena.z() - 34, 0, Material.STONE_BRICKS);
        }
    }

    private static void add(List<StructurePlacement> placements, StructureTemplateManager templates, String key, int x, int y, int z, int rotation, Material foundation) {
        StructureTemplate template = templates.get(key);
        if (template != null) {
            placements.add(new StructurePlacement(template, x, y, z, rotation, foundation));
        }
    }
}
