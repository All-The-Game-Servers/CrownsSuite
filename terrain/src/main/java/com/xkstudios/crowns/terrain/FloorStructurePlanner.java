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
            for (SettlementAnchor anchor : firstHavenAnchors(firstHaven)) {
                Material foundation = anchor.district() == TerrainDistrict.FARMING ? Material.DIRT : anchor.district() == TerrainDistrict.MARKET ? theme.road() : Material.STONE_BRICKS;
                add(placements, templates, anchor.templateKey(), anchor.x(), anchor.y(), anchor.z(), anchor.rotation(), foundation);
            }
            add(placements, templates, "spawn_plaza", firstHaven.x(), firstHaven.y(), firstHaven.z(), 0, theme.road());
            add(placements, templates, "notice_board", firstHaven.x() - 10, firstHaven.y(), firstHaven.z() - 16, 0, theme.road());
            add(placements, templates, "waystone", firstHaven.x() + 15, firstHaven.y(), firstHaven.z() - 2, 0, theme.road());
            add(placements, templates, "large_shrine", firstHaven.x() - 36, firstHaven.y(), firstHaven.z() + 30, 1, Material.STONE_BRICKS);
            add(placements, templates, "hillside_house", firstHaven.x() - 42, firstHaven.y(), firstHaven.z() - 28, 0, Material.STONE_BRICKS);
            add(placements, templates, "haven_house", firstHaven.x() + 38, firstHaven.y(), firstHaven.z() - 24, 2, Material.STONE_BRICKS);
            add(placements, templates, "hillside_house", firstHaven.x() - 36, firstHaven.y(), firstHaven.z() + 16, 1, Material.STONE_BRICKS);
            add(placements, templates, "market_stall", firstHaven.x() + 34, firstHaven.y(), firstHaven.z() + 24, 3, theme.road());
            add(placements, templates, "watchtower", firstHaven.x() + 56, firstHaven.y(), firstHaven.z() + 48, 0, Material.STONE_BRICKS);
            add(placements, templates, "terraced_farm", firstHaven.x() - 4, firstHaven.y(), firstHaven.z() + 58, 0, Material.DIRT);
            add(placements, templates, "farm_plot", firstHaven.x() + 24, firstHaven.y(), firstHaven.z() + 52, 0, Material.DIRT);
            add(placements, templates, "retaining_wall", firstHaven.x() - 48, firstHaven.y(), firstHaven.z() + 2, 1, Material.STONE_BRICKS);
            add(placements, templates, "switchback_stair", firstHaven.x() - 54, firstHaven.y(), firstHaven.z() - 8, 1, Material.STONE_BRICKS);
            add(placements, templates, "bridge", firstHaven.x() + 4, firstHaven.y(), firstHaven.z() + 84, 0, Material.COBBLESTONE);
            add(placements, templates, "gatehouse", firstHaven.x(), firstHaven.y(), firstHaven.z() - 62, 0, Material.STONE_BRICKS);
            add(placements, templates, "giant_tree_base", firstHaven.x() - 72, firstHaven.y(), firstHaven.z() + 72, 0, Material.ROOTED_DIRT);
        }
        for (TerrainPoint village : villages) {
            if (village == firstHaven) {
                continue;
            }
            if (village.key().contains("farm")) {
                add(placements, templates, "terraced_farm", village.x() - 12, village.y(), village.z(), 0, Material.DIRT);
                add(placements, templates, "farm_plot", village.x() + 12, village.y(), village.z(), 0, Material.DIRT);
                add(placements, templates, "mill", village.x(), village.y(), village.z() - 24, 0, Material.STONE_BRICKS);
                add(placements, templates, "hillside_house", village.x() + 28, village.y(), village.z() - 8, 1, Material.STONE_BRICKS);
            } else {
                add(placements, templates, "watchtower", village.x(), village.y(), village.z(), 0, Material.STONE_BRICKS);
                add(placements, templates, "gatehouse", village.x() - 18, village.y(), village.z() + 18, 1, Material.STONE_BRICKS);
                add(placements, templates, "retaining_wall", village.x() + 18, village.y(), village.z() + 8, 0, Material.STONE_BRICKS);
            }
        }
        for (TerrainPoint point : livingPoints) {
            switch (point.type()) {
                case "camp" -> add(placements, templates, "stream_camp", point.x(), point.y(), point.z(), 0, theme.road());
                case "waystone" -> add(placements, templates, "waystone", point.x(), point.y(), point.z(), 0, theme.road());
                case "road_marker" -> add(placements, templates, "road_marker", point.x(), point.y(), point.z(), 0, theme.road());
                case "shrine" -> add(placements, templates, "starter_shrine", point.x(), point.y(), point.z(), 0, theme.road());
                default -> {
                }
            }
        }
        for (TerrainPoint landmark : landmarks) {
            add(placements, templates, "landmark_spire", landmark.x(), landmark.y(), landmark.z(), 0, Material.STONE_BRICKS);
            add(placements, templates, "giant_tree_base", landmark.x() + 22, landmark.y(), landmark.z() - 18, 0, Material.ROOTED_DIRT);
        }
        if (arena != null) {
            add(placements, templates, "gate_arena_core", arena.x(), arena.y(), arena.z(), 0, Material.STONE_BRICKS);
            add(placements, templates, "gatehouse", arena.x(), arena.y(), arena.z() - 58, 0, Material.STONE_BRICKS);
            add(placements, templates, "gate_arch", arena.x(), arena.y(), arena.z() - 34, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruined_gate_marker", arena.x() - 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
            add(placements, templates, "ruined_gate_marker", arena.x() + 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
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

    public static List<SettlementAnchor> firstHavenAnchors(TerrainPoint firstHaven) {
        if (firstHaven == null) {
            return List.of();
        }
        return List.of(
                new SettlementAnchor("market-square", TerrainDistrict.CIVIC, firstHaven.x(), firstHaven.y(), firstHaven.z() - 8, "town_hall", 0),
                new SettlementAnchor("bridgehead-gate", TerrainDistrict.DEFENSIVE_EDGE, firstHaven.x(), firstHaven.y(), firstHaven.z() - 62, "gatehouse", 0),
                new SettlementAnchor("farm-terraces", TerrainDistrict.FARMING, firstHaven.x() - 6, firstHaven.y(), firstHaven.z() + 68, "terraced_farm", 0),
                new SettlementAnchor("mill-creek", TerrainDistrict.ROAD_EDGE, firstHaven.x() + 48, firstHaven.y(), firstHaven.z() + 64, "mill", 1)
        );
    }

    private static void add(List<StructurePlacement> placements, StructureTemplateManager templates, String key, int x, int y, int z, int rotation, Material foundation) {
        StructureTemplate template = templates.get(key);
        if (template != null) {
            placements.add(new StructurePlacement(template, x, y, z, rotation, foundation));
        }
    }
}
