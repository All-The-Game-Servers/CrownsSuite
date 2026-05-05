package com.xkstudios.crowns.terrain;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

public final class BlueprintDebugRenderer {
    private static final int SIZE = 512;
    private static final int WORLD_MIN = -768;
    private static final int WORLD_MAX = 1152;

    private BlueprintDebugRenderer() {
    }

    public static List<File> render(File outputDir, FloorBlueprint blueprint) throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Could not create debug map directory " + outputDir.getAbsolutePath());
        }
        File height = new File(outputDir, "floor-" + blueprint.floor() + "-height.png");
        File slope = new File(outputDir, "floor-" + blueprint.floor() + "-slope.png");
        File moisture = new File(outputDir, "floor-" + blueprint.floor() + "-moisture.png");
        File biome = new File(outputDir, "floor-" + blueprint.floor() + "-biomes.png");
        File roads = new File(outputDir, "floor-" + blueprint.floor() + "-roads.png");
        File parcels = new File(outputDir, "floor-" + blueprint.floor() + "-parcels.png");
        File landmarks = new File(outputDir, "floor-" + blueprint.floor() + "-landmarks.png");
        File qa = new File(outputDir, "floor-" + blueprint.floor() + "-qa.png");
        ImageIO.write(map(blueprint, MapKind.HEIGHT), "png", height);
        ImageIO.write(map(blueprint, MapKind.SLOPE), "png", slope);
        ImageIO.write(map(blueprint, MapKind.MOISTURE), "png", moisture);
        ImageIO.write(map(blueprint, MapKind.BIOME), "png", biome);
        ImageIO.write(overlay(blueprint, MapKind.ROADS), "png", roads);
        ImageIO.write(overlay(blueprint, MapKind.PARCELS), "png", parcels);
        ImageIO.write(overlay(blueprint, MapKind.LANDMARKS), "png", landmarks);
        ImageIO.write(qa(blueprint), "png", qa);
        return List.of(height, slope, moisture, biome, roads, parcels, landmarks, qa);
    }

    private static BufferedImage map(FloorBlueprint blueprint, MapKind kind) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int px = 0; px < SIZE; px++) {
            for (int pz = 0; pz < SIZE; pz++) {
                int x = world(px);
                int z = world(pz);
                Color color = switch (kind) {
                    case HEIGHT -> gradient((blueprint.surfaceHeight(x, z) - 58) / 66.0D, new Color(38, 66, 42), new Color(218, 225, 188));
                    case SLOPE -> gradient(Math.min(1.0D, blueprint.slope(x, z) / 3.0D), new Color(38, 80, 40), new Color(210, 36, 32));
                    case MOISTURE -> gradient(blueprint.moisture(x, z), new Color(132, 105, 48), new Color(34, 103, 190));
                    case BIOME -> biomeColor(blueprint.biomeKey(x, z));
                    default -> Color.BLACK;
                };
                image.setRGB(px, pz, color.getRGB());
            }
        }
        return image;
    }

    private static BufferedImage overlay(FloorBlueprint blueprint, MapKind kind) {
        BufferedImage image = map(blueprint, MapKind.BIOME);
        Graphics2D graphics = image.createGraphics();
        try {
            if (kind == MapKind.ROADS) {
                graphics.setColor(new Color(245, 220, 130));
                for (FloorBlueprint.Road road : blueprint.roads()) {
                    graphics.drawLine(pixel(road.from().x()), pixel(road.from().z()), pixel(road.to().x()), pixel(road.to().z()));
                }
            }
            if (kind == MapKind.PARCELS) {
                for (FloorBlueprint.Parcel parcel : blueprint.parcels()) {
                    graphics.setColor(parcelColor(parcel.district()));
                    int x = pixel(parcel.minX());
                    int z = pixel(parcel.minZ());
                    graphics.drawRect(x, z, Math.max(1, pixel(parcel.maxX()) - x), Math.max(1, pixel(parcel.maxZ()) - z));
                }
            }
            if (kind == MapKind.LANDMARKS) {
                graphics.setColor(Color.WHITE);
                for (FloorBlueprint.Node node : blueprint.nodes()) {
                    int px = pixel(node.x());
                    int pz = pixel(node.z());
                    graphics.fillOval(px - 3, pz - 3, 7, 7);
                    graphics.drawString(node.key(), px + 5, pz - 5);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage qa(FloorBlueprint blueprint) {
        BufferedImage image = overlay(blueprint, MapKind.ROADS);
        Graphics2D graphics = image.createGraphics();
        try {
            FloorBlueprint.Metrics metrics = blueprint.metrics();
            graphics.setColor(new Color(0, 0, 0, 180));
            graphics.fillRect(8, 8, 270, 92);
            graphics.setColor(Color.WHITE);
            graphics.drawString("QA score: " + String.format("%.2f", metrics.qaScore()), 16, 28);
            graphics.drawString("Avg road slope: " + String.format("%.2f", metrics.averageRoadSlope()), 16, 44);
            graphics.drawString("Max road slope: " + String.format("%.2f", metrics.maxRoadSlope()), 16, 60);
            graphics.drawString("Parcels: " + metrics.parcels() + "  Decorations: " + metrics.decorations(), 16, 76);
            graphics.drawString("Biome samples: " + metrics.biomeSamples(), 16, 92);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Color gradient(double value, Color low, Color high) {
        double safe = Math.max(0.0D, Math.min(1.0D, value));
        int r = (int) Math.round(low.getRed() * (1.0D - safe) + high.getRed() * safe);
        int g = (int) Math.round(low.getGreen() * (1.0D - safe) + high.getGreen() * safe);
        int b = (int) Math.round(low.getBlue() * (1.0D - safe) + high.getBlue() * safe);
        return new Color(r, g, b);
    }

    private static Color biomeColor(String biome) {
        return switch (biome) {
            case "old_growth" -> new Color(30, 82, 42);
            case "broken_highlands" -> new Color(98, 104, 92);
            case "frontier_fields" -> new Color(142, 162, 74);
            case "riverlands" -> new Color(55, 122, 150);
            case "shrine_ridge" -> new Color(93, 114, 76);
            case "gate_wilds" -> new Color(108, 82, 72);
            case "road_edge" -> new Color(152, 132, 92);
            default -> new Color(92, 148, 76);
        };
    }

    private static Color parcelColor(String district) {
        return switch (district) {
            case "civic" -> Color.WHITE;
            case "residential" -> Color.YELLOW;
            case "market" -> Color.ORANGE;
            case "farming" -> Color.GREEN;
            case "defensive" -> Color.RED;
            case "arena" -> Color.MAGENTA;
            default -> Color.CYAN;
        };
    }

    private static int pixel(int world) {
        return (int) Math.round((world - WORLD_MIN) / (double) (WORLD_MAX - WORLD_MIN) * (SIZE - 1));
    }

    private static int world(int pixel) {
        return (int) Math.round(WORLD_MIN + pixel / (double) (SIZE - 1) * (WORLD_MAX - WORLD_MIN));
    }

    private enum MapKind {
        HEIGHT,
        SLOPE,
        MOISTURE,
        BIOME,
        ROADS,
        PARCELS,
        LANDMARKS
    }
}
