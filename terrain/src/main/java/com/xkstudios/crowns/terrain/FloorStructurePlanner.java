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
            String worldName,
            TerrainTheme theme,
            StructureTemplateManager templates,
            List<TerrainPoint> villages,
            TerrainPoint arena,
            List<TerrainPoint> landmarks,
            List<TerrainPoint> livingPoints
    ) {
        List<StructurePlacement> placements = new ArrayList<>();
        if (floor != 1) {
            addSimpleAdventureFloor(floor, worldName, placements, templates, theme, villages, arena, landmarks);
            return placements;
        }
        TerrainPoint firstHaven = villages.stream()
                .filter(point -> point.key().equalsIgnoreCase("first-haven") || point.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(villages.isEmpty() ? null : villages.get(0));
        if (firstHaven != null) {
            for (SettlementAnchor anchor : firstHavenAnchors(firstHaven)) {
                Material foundation = anchor.district() == TerrainDistrict.FARMING ? Material.DIRT : anchor.district() == TerrainDistrict.MARKET ? theme.road() : Material.STONE_BRICKS;
                add(floor, worldName, placements, templates, anchor.templateKey(), anchor.x(), anchor.y(), anchor.z(), anchor.rotation(), foundation);
            }
            add(floor, worldName, placements, templates, "fh_spawn_plaza_grand", firstHaven.x(), firstHaven.y(), firstHaven.z(), 0, theme.road());
            add(floor, worldName, placements, templates, "fh_waystone_platform", firstHaven.x() + 24, firstHaven.y(), firstHaven.z() - 2, 0, theme.road());
            add(floor, worldName, placements, templates, "notice_board", firstHaven.x() - 20, firstHaven.y(), firstHaven.z() - 18, 0, theme.road());
            add(floor, worldName, placements, templates, "fh_shrine_grove", firstHaven.x() - 60, firstHaven.y(), firstHaven.z() + 42, 1, Material.MOSS_BLOCK);
            add(floor, worldName, placements, templates, "fh_house_large_a", firstHaven.x() - 54, firstHaven.y(), firstHaven.z() - 34, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_house_large_b", firstHaven.x() + 58, firstHaven.y(), firstHaven.z() - 34, 2, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_house_row", firstHaven.x() - 48, firstHaven.y(), firstHaven.z() + 8, 1, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_house_row", firstHaven.x() + 70, firstHaven.y(), firstHaven.z() + 8, 3, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_market_street", firstHaven.x() + 54, firstHaven.y(), firstHaven.z() + 40, 1, theme.road());
            add(floor, worldName, placements, templates, "fh_market_hall", firstHaven.x() + 82, firstHaven.y(), firstHaven.z() + 18, 3, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_blacksmith_large", firstHaven.x() + 82, firstHaven.y(), firstHaven.z() - 20, 3, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_watchtower_tall", firstHaven.x() + 72, firstHaven.y(), firstHaven.z() + 78, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_barn_large", firstHaven.x() - 54, firstHaven.y(), firstHaven.z() + 100, 1, Material.DIRT);
            add(floor, worldName, placements, templates, "fh_road_straight", firstHaven.x(), firstHaven.y(), firstHaven.z() - 38, 0, theme.road());
            add(floor, worldName, placements, templates, "fh_road_curve", firstHaven.x() + 34, firstHaven.y(), firstHaven.z() + 24, 1, theme.road());
            add(floor, worldName, placements, templates, "retaining_wall", firstHaven.x() - 78, firstHaven.y(), firstHaven.z() + 2, 1, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "switchback_stair", firstHaven.x() - 86, firstHaven.y(), firstHaven.z() - 12, 1, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_watchtower_tall", firstHaven.x() - 32, firstHaven.y(), firstHaven.z() - 94, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_watchtower_tall", firstHaven.x() + 32, firstHaven.y(), firstHaven.z() - 94, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "wall_fragment", firstHaven.x() - 62, firstHaven.y(), firstHaven.z() - 82, 1, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "wall_fragment", firstHaven.x() + 62, firstHaven.y(), firstHaven.z() - 82, 1, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_cliff_rocks", firstHaven.x() - 108, firstHaven.y(), firstHaven.z() - 20, 1, Material.STONE_BRICKS);
        }
        for (TerrainPoint village : villages) {
            if (village == firstHaven) {
                continue;
            }
            if (village.key().contains("farm")) {
                add(floor, worldName, placements, templates, "fh_farm_terrace_stamp", village.x() - 12, village.y(), village.z(), 0, Material.DIRT);
                add(floor, worldName, placements, templates, "farm_plot", village.x() + 12, village.y(), village.z(), 0, Material.DIRT);
                add(floor, worldName, placements, templates, "fh_barn_large", village.x() - 42, village.y(), village.z() + 18, 1, Material.DIRT);
                add(floor, worldName, placements, templates, "fh_house_large_a", village.x() + 44, village.y(), village.z() - 8, 1, Material.STONE_BRICKS);
            } else {
                add(floor, worldName, placements, templates, "fh_watchtower_tall", village.x(), village.y(), village.z(), 0, Material.STONE_BRICKS);
                add(floor, worldName, placements, templates, "fh_gatehouse_grand", village.x() - 28, village.y(), village.z() + 22, 1, Material.STONE_BRICKS);
                add(floor, worldName, placements, templates, "retaining_wall", village.x() + 18, village.y(), village.z() + 8, 0, Material.STONE_BRICKS);
            }
        }
        for (TerrainPoint point : livingPoints) {
            switch (point.type()) {
                case "camp" -> add(floor, worldName, placements, templates, "fh_starter_camp", point.x(), point.y(), point.z(), 0, theme.road());
                case "waystone" -> add(floor, worldName, placements, templates, "fh_waystone_platform", point.x(), point.y(), point.z(), 0, theme.road());
                case "road_marker" -> add(floor, worldName, placements, templates, "road_marker", point.x(), point.y(), point.z(), 0, theme.road());
                case "shrine" -> add(floor, worldName, placements, templates, "fh_shrine_grove", point.x(), point.y(), point.z(), 0, theme.road());
                default -> {
                }
            }
        }
        for (TerrainPoint landmark : landmarks) {
            add(floor, worldName, placements, templates, "landmark_spire", landmark.x(), landmark.y(), landmark.z(), 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_cliff_rocks", landmark.x() + 22, landmark.y(), landmark.z() - 18, 0, Material.ROOTED_DIRT);
        }
        if (arena != null) {
            add(floor, worldName, placements, templates, "fh_first_gate_platform", arena.x(), arena.y(), arena.z(), 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_arena_approach", arena.x(), arena.y(), arena.z() - 86, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "arena_staging", arena.x(), arena.y(), arena.z() - 132, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "arena_threshold", arena.x(), arena.y(), arena.z() - 54, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "fh_gatehouse_grand", arena.x(), arena.y(), arena.z() - 68, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "gate_arch", arena.x(), arena.y(), arena.z() - 34, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "ruined_gate_marker", arena.x() - 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "ruined_gate_marker", arena.x() + 42, arena.y(), arena.z() - 42, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "ruin_pillar", arena.x() - 42, arena.y(), arena.z() + 42, 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "ruin_pillar", arena.x() + 42, arena.y(), arena.z() + 42, 0, Material.STONE_BRICKS);
        }
        return List.copyOf(placements);
    }

    private static void addSimpleAdventureFloor(int floor, String worldName, List<StructurePlacement> placements, StructureTemplateManager templates, TerrainTheme theme, List<TerrainPoint> villages, TerrainPoint arena, List<TerrainPoint> landmarks) {
        for (TerrainPoint village : villages) {
            add(floor, worldName, placements, templates, "watchtower", village.x(), village.y(), village.z(), 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "camp", village.x() + 24, village.y(), village.z() + 24, 0, theme.road());
        }
        for (TerrainPoint landmark : landmarks) {
            add(floor, worldName, placements, templates, "landmark_spire", landmark.x(), landmark.y(), landmark.z(), 0, Material.STONE_BRICKS);
        }
        if (arena != null) {
            add(floor, worldName, placements, templates, "gate_arena_core", arena.x(), arena.y(), arena.z(), 0, Material.STONE_BRICKS);
            add(floor, worldName, placements, templates, "gate_arch", arena.x(), arena.y(), arena.z() - 34, 0, Material.STONE_BRICKS);
        }
    }

    public static List<SettlementAnchor> firstHavenAnchors(TerrainPoint firstHaven) {
        if (firstHaven == null) {
            return List.of();
        }
        return List.of(
                new SettlementAnchor("market-square", TerrainDistrict.CIVIC, firstHaven.x(), firstHaven.y(), firstHaven.z() - 50, "fh_town_hall_grand", 0),
                new SettlementAnchor("bridgehead-gate", TerrainDistrict.DEFENSIVE_EDGE, firstHaven.x(), firstHaven.y(), firstHaven.z() - 82, "fh_gatehouse_grand", 0),
                new SettlementAnchor("farm-terraces", TerrainDistrict.FARMING, firstHaven.x() - 6, firstHaven.y(), firstHaven.z() + 90, "fh_farm_terrace_stamp", 0),
                new SettlementAnchor("mill-creek", TerrainDistrict.ROAD_EDGE, firstHaven.x() + 48, firstHaven.y(), firstHaven.z() + 120, "fh_bridge_stream", 1)
        );
    }

    private static void add(int floor, String worldName, List<StructurePlacement> placements, StructureTemplateManager templates, String key, int x, int y, int z, int rotation, Material foundation) {
        StructureTemplate template = templates.get(key);
        if (template != null) {
            placements.add(new StructurePlacement(template, x, fitBaseY(floor, worldName, template, x, y, z, rotation), z, rotation, foundation));
        }
    }

    private static int fitBaseY(int floor, String worldName, StructureTemplate template, int originX, int fallbackY, int originZ, int rotation) {
        List<int[]> footprint = template.footprint(rotation);
        if (footprint.isEmpty()) {
            return fallbackY;
        }
        List<Integer> heights = new ArrayList<>();
        int sampleStride = Math.max(1, footprint.size() / 64);
        for (int i = 0; i < footprint.size(); i += sampleStride) {
            int[] point = footprint.get(i);
            heights.add(TerrainLayout.surfaceHeight(floor, worldName, originX + point[0], originZ + point[1]) + 1);
        }
        heights.sort(Integer::compareTo);
        int median = heights.get(heights.size() / 2);
        int lowest = heights.get(0);
        int highest = heights.get(heights.size() - 1);
        int fitted = median;
        if (highest - lowest <= 3) {
            fitted = (lowest + highest) / 2;
        }
        return Math.max(fallbackY - 10, Math.min(fallbackY + 10, fitted));
    }
}
