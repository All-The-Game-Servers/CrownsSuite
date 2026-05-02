package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.bukkit.configuration.ConfigurationSection;

public final class TerrainLayout {
    private TerrainLayout() {
    }

    public static int surfaceHeight(int floor, String worldName, int x, int z) {
        TerrainTheme theme = TerrainTheme.forFloor(floor, null);
        double continent = fractalNoise(worldName, floor, x, z, 260.0D, 3);
        double hills = fractalNoise(worldName, floor + 17, x, z, floor == 1 ? 92.0D : 70.0D, 4);
        double ridges = ridgeNoise(worldName, floor + 31, x, z, floor == 1 ? 150.0D : 98.0D);
        double valley = fractalNoise(worldName, floor + 47, x, z, 420.0D, 2);
        double floorScale = floor == 1 ? 0.58D : Math.min(1.65D, 0.90D + floor * 0.18D);
        int height = theme.baseHeight()
                + (int) Math.round(continent * 13.0D * floorScale)
                + (int) Math.round(hills * 9.0D * floorScale)
                + (int) Math.round(ridges * 10.0D * floorScale)
                - (int) Math.round(Math.max(0.0D, -valley) * 8.0D);
        for (TerrainPoint village : defaultVillages(floor, worldName, null)) {
            int distance = Math.max(Math.abs(x - village.x()), Math.abs(z - village.z()));
            if (distance <= 58) {
                height = blend(height, village.y(), 58 - distance, 58);
            }
        }
        TerrainPoint arena = defaultArena(floor, worldName, null);
        int arenaDistance = arena == null ? Integer.MAX_VALUE : (int) Math.round(Math.hypot(x - arena.x(), z - arena.z()));
        if (arenaDistance <= 80) {
            height = blend(height, arena.y(), 80 - arenaDistance, 80);
        }
        return Math.max(42, Math.min(130, height));
    }

    public static TerrainPoint defaultArena(int floor, String worldName, ConfigurationSection floorConfig) {
        String key = floorConfig == null ? null : floorConfig.getString("arena.key");
        String name = floorConfig == null ? null : floorConfig.getString("arena.name");
        ConfigurationSection arena = floorConfig == null ? null : floorConfig.getConfigurationSection("arena");
        int[] chosen = configuredOrProcedural(floor, worldName, arena, "arena", 0, List.of());
        int x = chosen[0];
        int z = chosen[1];
        int y = surfaceHeightWithoutFeatures(floor, worldName, x, z) + 1;
        return new TerrainPoint(floor, worldName, "arena", key == null ? "floor-" + floor + "-arena" : key,
                name == null ? "Floor " + floor + " Gate Arena" : name, x, y, z);
    }

    public static List<TerrainPoint> defaultVillages(int floor, String worldName, ConfigurationSection floorConfig) {
        List<TerrainPoint> points = new ArrayList<>();
        ConfigurationSection villages = floorConfig == null ? null : floorConfig.getConfigurationSection("villages");
        if (villages != null) {
            for (String child : villages.getKeys(false)) {
                ConfigurationSection section = villages.getConfigurationSection(child);
                if (section != null) {
                    int[] chosen = configuredOrProcedural(floor, worldName, section, "village", points.size(), points);
                    addVillage(points, floor, worldName, section.getString("key", child), section.getString("name", pretty(child)),
                            chosen[0],
                            chosen[1]);
                }
            }
        }
        if (points.isEmpty()) {
            int[] chosen = configuredOrProcedural(floor, worldName, null, "village", 0, points);
            addVillage(points, floor, worldName, "floor-" + floor + "-village", floor == 1 ? "First Haven" : "Floor " + floor + " Outpost",
                    chosen[0], chosen[1]);
            if (floor == 2) {
                int[] market = configuredOrProcedural(floor, worldName, null, "village", 1, points);
                addVillage(points, floor, worldName, "ridge-market", "Ridge Market", market[0], market[1]);
            }
        }
        return points;
    }

    public static List<TerrainPoint> defaultLandmarks(int floor, String worldName) {
        return defaultLandmarks(floor, worldName, null);
    }

    public static List<TerrainPoint> defaultLandmarks(int floor, String worldName, ConfigurationSection floorConfig) {
        List<TerrainPoint> points = new ArrayList<>();
        ConfigurationSection landmarks = floorConfig == null ? null : floorConfig.getConfigurationSection("landmarks");
        if (landmarks != null) {
            for (String child : landmarks.getKeys(false)) {
                ConfigurationSection section = landmarks.getConfigurationSection(child);
                if (section != null) {
                    int[] chosen = configuredOrProcedural(floor, worldName, section, "landmark", points.size(), points);
                    int x = chosen[0];
                    int z = chosen[1];
                    points.add(new TerrainPoint(floor, worldName, "landmark", section.getString("key", child),
                            section.getString("name", pretty(child)), x, surfaceHeight(floor, worldName, x, z) + 1, z));
                }
            }
        }
        if (points.isEmpty()) {
            int[] chosen = configuredOrProcedural(floor, worldName, floorConfig, "landmark", 0, points);
            int x = chosen[0];
            int z = chosen[1];
            points.add(new TerrainPoint(floor, worldName, "landmark", "floor-" + floor + "-spire",
                    "Floor " + floor + " Waystone", x, surfaceHeight(floor, worldName, x, z) + 1, z));
        }
        return points;
    }

    public static List<TerrainPoint> defaultTypedPoints(int floor, String worldName, ConfigurationSection floorConfig, String type) {
        List<TerrainPoint> points = new ArrayList<>();
        String sectionName = typeToSection(type);
        ConfigurationSection configured = floorConfig == null ? null : floorConfig.getConfigurationSection(sectionName);
        if (configured != null) {
            for (String child : configured.getKeys(false)) {
                ConfigurationSection section = configured.getConfigurationSection(child);
                if (section != null) {
                    int[] chosen = configuredOrProcedural(floor, worldName, section, type, points.size(), points);
                    addPoint(points, floor, worldName, type, section.getString("key", child), section.getString("name", pretty(child)),
                            chosen[0],
                            chosen[1]);
                }
            }
        }
        if (points.isEmpty() && floor == 1) {
            switch (type) {
                case "camp" -> {
                    int[] first = configuredOrProcedural(floor, worldName, null, type, 0, points);
                    addPoint(points, floor, worldName, type, "river-camp", "River Camp", first[0], first[1]);
                    int[] second = configuredOrProcedural(floor, worldName, null, type, 1, points);
                    addPoint(points, floor, worldName, type, "pine-rest", "Pine Rest", second[0], second[1]);
                }
                case "waystone" -> {
                    int[] chosen = configuredOrProcedural(floor, worldName, null, type, 0, points);
                    addPoint(points, floor, worldName, type, "first-waystone", "First Haven Waystone", chosen[0], chosen[1]);
                }
                case "road_marker" -> {
                    int[] first = configuredOrProcedural(floor, worldName, null, type, 0, points);
                    addPoint(points, floor, worldName, type, "north-road", "North Road Marker", first[0], first[1]);
                    int[] second = configuredOrProcedural(floor, worldName, null, type, 1, points);
                    addPoint(points, floor, worldName, type, "farm-road", "Farm Road Marker", second[0], second[1]);
                }
                case "shrine" -> {
                    int[] chosen = configuredOrProcedural(floor, worldName, null, type, 0, points);
                    addPoint(points, floor, worldName, type, "starter-shrine", "Starter Shrine", chosen[0], chosen[1]);
                }
                default -> {
                }
            }
        }
        return points;
    }

    public static long layoutSeed(String worldName, int floor) {
        long seed = 1125899906842597L;
        String key = (worldName == null ? "world" : worldName).toLowerCase(Locale.ROOT);
        for (int i = 0; i < key.length(); i++) {
            seed = 31L * seed + key.charAt(i);
        }
        return seed ^ (floor * 341873128712L);
    }

    public static Random seededRandom(String worldName, int floor, int salt) {
        return new Random(layoutSeed(worldName, floor) ^ (salt * 132897987541L));
    }

    private static void addVillage(List<TerrainPoint> points, int floor, String worldName, String key, String name, int x, int z) {
        int y = surfaceHeightWithoutFeatures(floor, worldName, x, z) + 1;
        points.add(new TerrainPoint(floor, worldName, "village", key, name, x, y, z));
    }

    private static void addPoint(List<TerrainPoint> points, int floor, String worldName, String type, String key, String name, int x, int z) {
        int y = surfaceHeightWithoutFeatures(floor, worldName, x, z) + 1;
        points.add(new TerrainPoint(floor, worldName, type, key, name, x, y, z));
    }

    private static int surfaceHeightWithoutFeatures(int floor, String worldName, int x, int z) {
        TerrainTheme theme = TerrainTheme.forFloor(floor, null);
        double continent = fractalNoise(worldName, floor, x, z, 260.0D, 3);
        double hills = fractalNoise(worldName, floor + 17, x, z, floor == 1 ? 92.0D : 70.0D, 4);
        double floorScale = floor == 1 ? 0.58D : Math.min(1.65D, 0.90D + floor * 0.18D);
        return Math.max(42, Math.min(130, theme.baseHeight()
                + (int) Math.round(continent * 13.0D * floorScale)
                + (int) Math.round(hills * 9.0D * floorScale)));
    }

    private static int[] configuredOrProcedural(int floor, String worldName, ConfigurationSection section, String type, int index, List<TerrainPoint> existing) {
        if (section != null && section.isSet("x") && section.isSet("z")) {
            return new int[]{section.getInt("x"), section.getInt("z")};
        }
        int size = worldSize(floor, section);
        int half = Math.max(512, size / 2);
        int margin = Math.max(192, Math.min(512, size / 16));
        int spawnSafe = spawnSafeRadius(section);
        Random random = seededRandom(worldName, floor, type.hashCode() + index * 31);
        int bestX = defaultPointX(floor, type, index);
        int bestZ = defaultPointZ(floor, type, index);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int attempt = 0; attempt < 96; attempt++) {
            int x = random.nextInt(Math.max(1, size - margin * 2)) - half + margin;
            int z = random.nextInt(Math.max(1, size - margin * 2)) - half + margin;
            if (Math.hypot(x, z) < spawnSafe) {
                continue;
            }
            boolean tooClose = false;
            for (TerrainPoint point : existing) {
                if (Math.hypot(x - point.x(), z - point.z()) < 260.0D) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            int y = surfaceHeightWithoutFeatures(floor, worldName, x, z);
            double waterPenalty = floor == 1 && y < 64 ? 24.0D : 0.0D;
            double centerBias = -Math.hypot(x, z) / (floor == 1 ? 9000.0D : 4500.0D);
            double heightScore = -Math.abs(y - TerrainTheme.forFloor(floor, null).baseHeight()) * 0.18D;
            double score = heightScore + centerBias - waterPenalty + random.nextDouble();
            if (type.equals("arena")) {
                score += Math.hypot(x, z) / (floor == 1 ? 9000.0D : 4500.0D);
            }
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
            }
        }
        return new int[]{bestX, bestZ};
    }

    private static int worldSize(int floor, ConfigurationSection section) {
        ConfigurationSection floorSection = section;
        while (floorSection != null && floorSection.getParent() != null && !floorSection.getCurrentPath().matches(".*\\.\\d+$")) {
            floorSection = floorSection.getParent();
        }
        if (floorSection != null && floorSection.isSet("world-size")) {
            return floorSection.getInt("world-size", floor == 1 ? 16000 : 8000);
        }
        return floor == 1 ? 16000 : 8000;
    }

    private static int spawnSafeRadius(ConfigurationSection section) {
        ConfigurationSection cursor = section;
        while (cursor != null) {
            if (cursor.isSet("spawn-safe-radius")) {
                return cursor.getInt("spawn-safe-radius", 192);
            }
            cursor = cursor.getParent();
        }
        return 192;
    }

    private static int hashNoise(String worldName, int floor, int x, int z) {
        long value = layoutSeed(worldName, floor) + x * 73428767L + z * 91227153L;
        value ^= value >>> 13;
        value *= 1274126177L;
        value ^= value >>> 16;
        return (int) Math.floorMod(value, 100000L);
    }

    private static double fractalNoise(String worldName, int floor, int x, int z, double scale, int octaves) {
        double total = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double max = 0.0D;
        for (int octave = 0; octave < octaves; octave++) {
            total += smoothNoise(worldName, floor + octave * 101, x / scale * frequency, z / scale * frequency) * amplitude;
            max += amplitude;
            amplitude *= 0.52D;
            frequency *= 2.03D;
        }
        return max <= 0.0D ? 0.0D : total / max;
    }

    private static double ridgeNoise(String worldName, int floor, int x, int z, double scale) {
        double value = fractalNoise(worldName, floor, x, z, scale, 3);
        return 1.0D - Math.abs(value);
    }

    private static double smoothNoise(String worldName, int floor, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = x - x0;
        double tz = z - z0;
        double a = valueNoise(worldName, floor, x0, z0);
        double b = valueNoise(worldName, floor, x0 + 1, z0);
        double c = valueNoise(worldName, floor, x0, z0 + 1);
        double d = valueNoise(worldName, floor, x0 + 1, z0 + 1);
        double ux = fade(tx);
        double uz = fade(tz);
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uz);
    }

    private static double valueNoise(String worldName, int floor, int x, int z) {
        return hashNoise(worldName, floor, x, z) / 50000.0D - 1.0D;
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static int blend(int current, int target, int weight, int max) {
        double amount = Math.max(0.0D, Math.min(1.0D, weight / (double) max));
        return (int) Math.round(current * (1.0D - amount) + target * amount);
    }

    private static int defaultVillageX(int floor, int index) {
        if (floor == 1) {
            return 192 + index * 320;
        }
        if (floor == 2) {
            return index == 0 ? 320 : -760;
        }
        return -360 + index * 420;
    }

    private static int defaultVillageZ(int floor, int index) {
        if (floor == 1) {
            return 160 - index * 280;
        }
        if (floor == 2) {
            return index == 0 ? -240 : 520;
        }
        return -420 + index * 360;
    }

    private static int defaultPointX(int floor, String type, int index) {
        int base = switch (type) {
            case "camp" -> 560;
            case "waystone" -> 64;
            case "road_marker" -> 320;
            case "shrine" -> 96;
            default -> 240;
        };
        return base + index * (floor == 1 ? -420 : 280);
    }

    private static int defaultPointZ(int floor, String type, int index) {
        int base = switch (type) {
            case "camp" -> 96;
            case "waystone" -> 64;
            case "road_marker" -> 0;
            case "shrine" -> -192;
            default -> 240;
        };
        return base + index * (floor == 1 ? 300 : -220);
    }

    private static String typeToSection(String type) {
        return switch (type) {
            case "road_marker" -> "road-markers";
            case "waystone" -> "waystones";
            case "camp" -> "camps";
            case "shrine" -> "shrines";
            default -> type + "s";
        };
    }

    private static int defaultArenaX(int floor) {
        return switch (floor) {
            case 1 -> 960;
            case 2 -> 1240;
            case 3 -> -1180;
            default -> 900 + floor * 180;
        };
    }

    private static int defaultArenaZ(int floor) {
        return switch (floor) {
            case 1 -> 768;
            case 2 -> 880;
            case 3 -> 1030;
            default -> 760 + floor * 140;
        };
    }

    private static String pretty(String key) {
        if (key == null || key.isBlank()) {
            return "Village";
        }
        String[] parts = key.replace('_', '-').split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
