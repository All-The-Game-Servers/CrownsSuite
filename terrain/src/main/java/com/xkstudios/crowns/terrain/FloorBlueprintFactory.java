package com.xkstudios.crowns.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class FloorBlueprintFactory {
    private FloorBlueprintFactory() {
    }

    public static FloorBlueprint create(int floor, String worldName, String profileVersion, long seed) {
        if (floor != 1) {
            return fallback(floor, worldName, profileVersion, seed);
        }
        List<FloorBlueprint.Node> nodes = new ArrayList<>();
        FloorBlueprint.Node haven = add(nodes, "village", "first-haven", "First Haven", 0, 70, 0, 170, "spawn");
        FloorBlueprint.Node market = add(nodes, "landmark", "market-square", "Market Square", 18, 70, 20, 70, "civic");
        FloorBlueprint.Node farmGate = add(nodes, "road_marker", "farm-gate", "Farm Gate", -190, 70, 125, 82, "farm");
        FloorBlueprint.Node camp = add(nodes, "camp", "starter-camp", "Starter Camp", 275, 70, -120, 64, "camp");
        FloorBlueprint.Node shrine = add(nodes, "shrine", "starter-shrine", "Starter Shrine", -90, 74, -180, 74, "shrine");
        FloorBlueprint.Node waystone = add(nodes, "waystone", "first-waystone", "First Haven Waystone", 18, 70, 12, 42, "travel");
        FloorBlueprint.Node northRoad = add(nodes, "road_marker", "north-road", "North Road Marker", 0, 72, -285, 46, "route");
        FloorBlueprint.Node approach = add(nodes, "landmark", "arena-approach", "Arena Approach", 720, 76, 610, 90, "sightline");
        FloorBlueprint.Node arena = add(nodes, "arena", "first-gate-arena", "First Gate Arena", 960, 78, 768, 118, "boss");

        List<FloorBlueprint.Road> roads = List.of(
                new FloorBlueprint.Road(haven, farmGate, 5, "haven-farm"),
                new FloorBlueprint.Road(haven, camp, 5, "haven-camp"),
                new FloorBlueprint.Road(haven, shrine, 5, "haven-shrine"),
                new FloorBlueprint.Road(haven, northRoad, 5, "haven-north"),
                new FloorBlueprint.Road(haven, approach, 6, "haven-arena-approach"),
                new FloorBlueprint.Road(approach, arena, 7, "approach-arena")
        );
        List<FloorBlueprint.Parcel> parcels = List.of(
                new FloorBlueprint.Parcel("civic-core", "civic", -78, -76, 92, 84, 70, "town-core"),
                new FloorBlueprint.Parcel("residential-west", "residential", -126, -48, -58, 72, 70, "homes"),
                new FloorBlueprint.Parcel("residential-east", "residential", 48, -60, 112, 78, 70, "homes"),
                new FloorBlueprint.Parcel("market-street", "market", 44, -26, 110, 44, 70, "market"),
                new FloorBlueprint.Parcel("farm-district", "farming", -244, 82, -128, 170, 70, "farms"),
                new FloorBlueprint.Parcel("defensive-gate", "defensive", 108, -26, 150, 24, 70, "gate"),
                new FloorBlueprint.Parcel("starter-camp", "wilderness", 244, -154, 312, -84, 70, "camp"),
                new FloorBlueprint.Parcel("starter-shrine", "shrine", -126, -216, -54, -144, 74, "shrine"),
                new FloorBlueprint.Parcel("first-gate-arena", "arena", 884, 690, 1036, 844, 78, "arena")
        );
        List<FloorBlueprint.Decoration> decorations = generateDecorations(seed, roads, parcels);
        FloorBlueprint blueprint = new FloorBlueprint(floor, worldName, profileVersion, seed, nodes, roads, parcels, decorations,
                new FloorBlueprint.Metrics(0.0D, 0.0D, 0, 0, parcels.size(), decorations.size(), 3, 0.0D));
        FloorBlueprint.Metrics metrics = measure(blueprint);
        return new FloorBlueprint(floor, worldName, profileVersion, seed, nodes, roads, parcels, decorations, metrics);
    }

    private static FloorBlueprint fallback(int floor, String worldName, String profileVersion, long seed) {
        FloorBlueprint.Node outpost = new FloorBlueprint.Node("village", "floor-" + floor + "-outpost", "Floor " + floor + " Outpost", 0, 70, 0, 80, "outpost");
        FloorBlueprint.Node arena = new FloorBlueprint.Node("arena", "floor-" + floor + "-arena", "Floor " + floor + " Arena", 640, 76, 640, 92, "boss");
        List<FloorBlueprint.Node> nodes = List.of(outpost, arena);
        List<FloorBlueprint.Road> roads = List.of(new FloorBlueprint.Road(outpost, arena, 5, "outpost-arena"));
        List<FloorBlueprint.Parcel> parcels = List.of(new FloorBlueprint.Parcel("outpost", "outpost", -48, -48, 48, 48, 70, "outpost"));
        return new FloorBlueprint(floor, worldName, profileVersion, seed, nodes, roads, parcels, List.of(),
                new FloorBlueprint.Metrics(0.0D, 0.0D, 0, 2, parcels.size(), 0, 0, 0.7D));
    }

    private static FloorBlueprint.Node add(List<FloorBlueprint.Node> nodes, String type, String key, String name, int x, int y, int z, int flattenRadius, String role) {
        FloorBlueprint.Node node = new FloorBlueprint.Node(type, key, name, x, y, z, flattenRadius, role);
        nodes.add(node);
        return node;
    }

    private static List<FloorBlueprint.Decoration> generateDecorations(long seed, List<FloorBlueprint.Road> roads, List<FloorBlueprint.Parcel> parcels) {
        List<FloorBlueprint.Decoration> result = new ArrayList<>();
        Random random = new Random(seed ^ 0xDEC0A7E5L);
        int spacing = 42;
        int index = 0;
        for (int gx = -18; gx <= 26; gx++) {
            for (int gz = -18; gz <= 26; gz++) {
                int x = gx * spacing + random.nextInt(25) - 12;
                int z = gz * spacing + random.nextInt(25) - 12;
                if (insideParcel(x, z, parcels) || distanceToRoad(x, z, roads) < 18.0D) {
                    continue;
                }
                double distance = Math.hypot(x, z);
                if (distance < 150.0D || distance > 1250.0D) {
                    continue;
                }
                String type;
                int radius;
                if (x < -420 && random.nextDouble() < 0.68D) {
                    type = "tree";
                    radius = 7;
                } else if (random.nextDouble() < 0.22D) {
                    type = "rock";
                    radius = 4;
                } else if (random.nextDouble() < 0.08D) {
                    type = "ruin";
                    radius = 8;
                } else {
                    continue;
                }
                result.add(new FloorBlueprint.Decoration(type + "-" + index++, type, x, z, radius));
            }
        }
        return result;
    }

    private static FloorBlueprint.Metrics measure(FloorBlueprint blueprint) {
        double totalSlope = 0.0D;
        double maxSlope = 0.0D;
        int samples = 0;
        for (FloorBlueprint.Road road : blueprint.roads()) {
            int roadSamples = Math.max(4, (int) Math.round(Math.hypot(road.to().x() - road.from().x(), road.to().z() - road.from().z()) / 24.0D));
            for (int i = 0; i <= roadSamples; i++) {
                double t = i / (double) roadSamples;
                int x = (int) Math.round(road.from().x() + (road.to().x() - road.from().x()) * t);
                int z = (int) Math.round(road.from().z() + (road.to().z() - road.from().z()) * t);
                double slope = blueprint.slope(x, z);
                totalSlope += slope;
                maxSlope = Math.max(maxSlope, slope);
                samples++;
            }
        }
        int biomeSamples = 0;
        List<String> biomes = new ArrayList<>();
        for (int x = -720; x <= 1120; x += 160) {
            for (int z = -640; z <= 960; z += 160) {
                String biome = blueprint.biomeKey(x, z);
                if (!biomes.contains(biome)) {
                    biomes.add(biome);
                }
                biomeSamples++;
            }
        }
        double average = samples == 0 ? 0.0D : totalSlope / samples;
        double qa = 1.0D;
        qa -= Math.max(0.0D, average - 0.8D) * 0.18D;
        qa -= Math.max(0.0D, maxSlope - 2.2D) * 0.08D;
        qa -= biomes.size() < 5 ? 0.18D : 0.0D;
        qa -= blueprint.parcels().size() < 7 ? 0.12D : 0.0D;
        qa = Math.max(0.0D, Math.min(1.0D, qa));
        return new FloorBlueprint.Metrics(average, maxSlope, 0, biomeSamples, blueprint.parcels().size(), blueprint.decorations().size(), 3, qa);
    }

    private static boolean insideParcel(int x, int z, List<FloorBlueprint.Parcel> parcels) {
        for (FloorBlueprint.Parcel parcel : parcels) {
            if (parcel.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToRoad(int x, int z, List<FloorBlueprint.Road> roads) {
        double best = Double.MAX_VALUE;
        for (FloorBlueprint.Road road : roads) {
            best = Math.min(best, distanceToSegment(x, z, road.from().x(), road.from().z(), road.to().x(), road.to().z()));
        }
        return best;
    }

    private static double distanceToSegment(double x, double z, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.001D) {
            return Math.hypot(x - x1, z - z1);
        }
        double t = ((x - x1) * dx + (z - z1) * dz) / lengthSquared;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return Math.hypot(x - (x1 + t * dx), z - (z1 + t * dz));
    }
}
