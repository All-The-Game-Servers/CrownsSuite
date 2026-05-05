package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FloorBlueprint {
    private final int floor;
    private final String worldName;
    private final String profileVersion;
    private final long seed;
    private final List<Node> nodes;
    private final List<Road> roads;
    private final List<Parcel> parcels;
    private final List<Decoration> decorations;
    private final Metrics metrics;

    public FloorBlueprint(int floor, String worldName, String profileVersion, long seed,
                          List<Node> nodes, List<Road> roads, List<Parcel> parcels,
                          List<Decoration> decorations, Metrics metrics) {
        this.floor = floor;
        this.worldName = worldName;
        this.profileVersion = profileVersion;
        this.seed = seed;
        this.nodes = List.copyOf(nodes);
        this.roads = List.copyOf(roads);
        this.parcels = List.copyOf(parcels);
        this.decorations = List.copyOf(decorations);
        this.metrics = metrics;
    }

    public int floor() {
        return this.floor;
    }

    public String worldName() {
        return this.worldName;
    }

    public String profileVersion() {
        return this.profileVersion;
    }

    public long seed() {
        return this.seed;
    }

    public List<Node> nodes() {
        return this.nodes;
    }

    public List<Road> roads() {
        return this.roads;
    }

    public List<Parcel> parcels() {
        return this.parcels;
    }

    public List<Decoration> decorations() {
        return this.decorations;
    }

    public Metrics metrics() {
        return this.metrics;
    }

    public int surfaceHeight(int x, int z) {
        double distance = Math.hypot(x, z);
        double basin = Math.max(0.0D, 1.0D - Math.min(1.0D, distance / 720.0D));
        double broad = fractal(x, z, 980.0D, 4, 0x51F00DL);
        double ridges = ridge(x, z, 390.0D, 0xBEEFL);
        double local = fractal(x, z, 170.0D, 3, 0xC0FFEE);
        double roadFlatten = Math.max(0.0D, 1.0D - this.distanceToRoad(x, z) / 18.0D);
        double riverLower = Math.max(0.0D, 1.0D - this.riverDistance(x, z) / 22.0D);
        int base = 70
                + (int) Math.round(broad * 7.0D)
                + (int) Math.round(local * 3.0D)
                + (int) Math.round(Math.max(0.0D, ridges) * this.ridgeWeight(x, z))
                - (int) Math.round(basin * 4.0D)
                - (int) Math.round(riverLower * 5.0D);
        for (Parcel parcel : this.parcels) {
            if (parcel.contains(x, z)) {
                base = blend(base, parcel.y() - 1, 0.82D);
            }
        }
        if (roadFlatten > 0.0D) {
            base = blend(base, this.roadY(x, z), roadFlatten * 0.9D);
        }
        for (Node node : this.nodes) {
            double nodeDistance = Math.hypot(x - node.x(), z - node.z());
            if (nodeDistance <= node.flattenRadius()) {
                double amount = 1.0D - nodeDistance / Math.max(1.0D, node.flattenRadius());
                base = blend(base, node.y() - 1, amount * 0.9D);
            }
        }
        return Math.max(58, Math.min(124, base));
    }

    public double moisture(int x, int z) {
        double wet = 0.48D + fractal(x, z, 520.0D, 4, 0xAD00ABL) * 0.22D;
        double river = Math.max(0.0D, 1.0D - this.riverDistance(x, z) / 90.0D) * 0.30D;
        return clamp(wet + river, 0.0D, 1.0D);
    }

    public double riverDistance(int x, int z) {
        double creek = Math.abs(z - (Math.sin((x + 180.0D) / 180.0D) * 34.0D - 210.0D));
        double farmCreek = Math.abs((z - 110.0D) - Math.sin((x + 240.0D) / 130.0D) * 18.0D);
        return Math.min(creek, farmCreek);
    }

    public boolean river(int x, int z) {
        return this.riverDistance(x, z) <= 5.5D;
    }

    public String biomeKey(int x, int z) {
        double moisture = this.moisture(x, z);
        int height = this.surfaceHeight(x, z);
        if (this.distanceToRoad(x, z) <= 12.0D) {
            return "road_edge";
        }
        if (x > 690 && z > 520) {
            return "gate_wilds";
        }
        if (z < -380 && height >= 74) {
            return "shrine_ridge";
        }
        if (x < -420 && moisture >= 0.45D) {
            return "old_growth";
        }
        if (z > 90 && x < -90) {
            return "frontier_fields";
        }
        if (moisture >= 0.72D) {
            return "riverlands";
        }
        if (height >= 79) {
            return "broken_highlands";
        }
        return "starter_basin";
    }

    public double slope(int x, int z) {
        int east = this.surfaceHeight(x + 4, z);
        int west = this.surfaceHeight(x - 4, z);
        int north = this.surfaceHeight(x, z - 4);
        int south = this.surfaceHeight(x, z + 4);
        return Math.max(Math.abs(east - west), Math.abs(north - south)) / 8.0D;
    }

    public double distanceToRoad(int x, int z) {
        double best = Double.MAX_VALUE;
        for (Road road : this.roads) {
            best = Math.min(best, distanceToSegment(x, z, road.from().x(), road.from().z(), road.to().x(), road.to().z()));
        }
        return best;
    }

    public int roadY(int x, int z) {
        Road best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Road road : this.roads) {
            double distance = distanceToSegment(x, z, road.from().x(), road.from().z(), road.to().x(), road.to().z());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = road;
            }
        }
        if (best == null) {
            return 70;
        }
        double total = Math.max(1.0D, Math.hypot(best.to().x() - best.from().x(), best.to().z() - best.from().z()));
        double t = ((x - best.from().x()) * (best.to().x() - best.from().x()) + (z - best.from().z()) * (best.to().z() - best.from().z())) / (total * total);
        t = clamp(t, 0.0D, 1.0D);
        return (int) Math.round(best.from().y() * (1.0D - t) + best.to().y() * t);
    }

    public List<TerrainPoint> pointsOfType(String type) {
        String normalized = normalize(type);
        List<TerrainPoint> points = new ArrayList<>();
        for (Node node : this.nodes) {
            if (node.type().equals(normalized)) {
                points.add(node.toPoint(this.floor, this.worldName));
            }
        }
        return points;
    }

    public Node node(String key) {
        String normalized = normalize(key);
        for (Node node : this.nodes) {
            if (normalize(node.key()).equals(normalized)) {
                return node;
            }
        }
        return null;
    }

    public long hash() {
        long value = this.seed ^ this.profileVersion.hashCode();
        for (Node node : this.nodes) {
            value = value * 31L + node.key().hashCode();
            value = value * 31L + node.x();
            value = value * 31L + node.z();
        }
        return value;
    }

    private double ridgeWeight(int x, int z) {
        if (x > 690 && z > 520) {
            return 13.0D;
        }
        if (z < -380) {
            return 10.0D;
        }
        return 5.0D;
    }

    private double fractal(int x, int z, double scale, int octaves, long salt) {
        double total = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double max = 0.0D;
        for (int octave = 0; octave < octaves; octave++) {
            total += smoothNoise(x / scale * frequency, z / scale * frequency, salt + octave * 131L) * amplitude;
            max += amplitude;
            amplitude *= 0.52D;
            frequency *= 2.03D;
        }
        return max <= 0.0D ? 0.0D : total / max;
    }

    private double ridge(int x, int z, double scale, long salt) {
        return 1.0D - Math.abs(this.fractal(x, z, scale, 3, salt));
    }

    private double smoothNoise(double x, double z, long salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = x - x0;
        double tz = z - z0;
        double a = valueNoise(x0, z0, salt);
        double b = valueNoise(x0 + 1, z0, salt);
        double c = valueNoise(x0, z0 + 1, salt);
        double d = valueNoise(x0 + 1, z0 + 1, salt);
        double ux = fade(tx);
        double uz = fade(tz);
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uz);
    }

    private double valueNoise(int x, int z, long salt) {
        long value = this.seed ^ salt ^ (x * 73428767L) ^ (z * 91227153L);
        value ^= value >>> 13;
        value *= 1274126177L;
        value ^= value >>> 16;
        return Math.floorMod(value, 100000L) / 50000.0D - 1.0D;
    }

    private static double distanceToSegment(double x, double z, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.001D) {
            return Math.hypot(x - x1, z - z1);
        }
        double t = ((x - x1) * dx + (z - z1) * dz) / lengthSquared;
        t = clamp(t, 0.0D, 1.0D);
        return Math.hypot(x - (x1 + t * dx), z - (z1 + t * dz));
    }

    private static int blend(int current, int target, double amount) {
        double safe = clamp(amount, 0.0D, 1.0D);
        return (int) Math.round(current * (1.0D - safe) + target * safe);
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public record Node(String type, String key, String displayName, int x, int y, int z, int flattenRadius, String role) {
        public TerrainPoint toPoint(int floor, String worldName) {
            return new TerrainPoint(floor, worldName, this.type, this.key, this.displayName, this.x, this.y, this.z);
        }
    }

    public record Road(Node from, Node to, int width, String key) {
    }

    public record Parcel(String key, String district, int minX, int minZ, int maxX, int maxZ, int y, String role) {
        public boolean contains(int x, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        public int centerX() {
            return (this.minX + this.maxX) / 2;
        }

        public int centerZ() {
            return (this.minZ + this.maxZ) / 2;
        }
    }

    public record Decoration(String key, String type, int x, int z, int radius) {
    }

    public record Metrics(double averageRoadSlope, double maxRoadSlope, int deadEndRoads, int biomeSamples,
                          int parcels, int decorations, int landmarks, double qaScore) {
    }
}
