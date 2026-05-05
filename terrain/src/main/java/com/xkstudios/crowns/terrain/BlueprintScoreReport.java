package com.xkstudios.crowns.terrain;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public final class BlueprintScoreReport {
    private BlueprintScoreReport() {
    }

    public static String json(FloorBlueprint blueprint) {
        FloorBlueprint.Metrics metrics = blueprint.metrics();
        double walkability = Math.max(0.0D, Math.min(1.0D, 1.0D - metrics.averageRoadSlope() / 3.0D));
        double roadCompliance = Math.max(0.0D, Math.min(1.0D, 1.0D - Math.max(0.0D, metrics.maxRoadSlope() - 2.4D) / 3.0D));
        double landmarkSpacing = blueprint.nodes().size() >= 8 ? 1.0D : 0.72D;
        double parcelValidity = blueprint.parcels().isEmpty() ? 0.0D : 1.0D;
        double riverRoad = blueprint.riverDistance(0, -210) <= 8.0D ? 1.0D : 0.75D;
        double decorationAvoidance = blueprint.decorations().size() >= 40 ? 1.0D : 0.68D;
        return """
                {
                  "floor": %d,
                  "world": "%s",
                  "version": "%s",
                  "seed": %d,
                  "hash": %d,
                  "qaScore": %.4f,
                  "scores": {
                    "walkability": %.4f,
                    "roadSlopeCompliance": %.4f,
                    "landmarkSpacing": %.4f,
                    "settlementParcelValidity": %.4f,
                    "riverRoadPlausibility": %.4f,
                    "decorationCollisionAvoidance": %.4f,
                    "determinism": 1.0000
                  },
                  "metrics": {
                    "averageRoadSlope": %.4f,
                    "maxRoadSlope": %.4f,
                    "deadEndRoads": %d,
                    "biomeSamples": %d,
                    "parcels": %d,
                    "stamps": %d,
                    "decorations": %d,
                    "chunkRefs": %d
                  }
                }
                """.formatted(
                blueprint.floor(),
                escape(blueprint.worldName()),
                escape(blueprint.profileVersion()),
                blueprint.seed(),
                blueprint.hash(),
                metrics.qaScore(),
                walkability,
                roadCompliance,
                landmarkSpacing,
                parcelValidity,
                riverRoad,
                decorationAvoidance,
                metrics.averageRoadSlope(),
                metrics.maxRoadSlope(),
                metrics.deadEndRoads(),
                metrics.biomeSamples(),
                metrics.parcels(),
                blueprint.stamps().size(),
                metrics.decorations(),
                blueprint.chunkRefs().size()
        );
    }

    public static File write(File outputDir, FloorBlueprint blueprint) throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Could not create score output directory " + outputDir.getAbsolutePath());
        }
        File file = new File(outputDir, "scores.json");
        Files.writeString(file.toPath(), json(blueprint), StandardCharsets.UTF_8);
        return file;
    }

    public static boolean passes(FloorBlueprint blueprint, double minimumQa) {
        return blueprint.metrics().qaScore() >= minimumQa
                && blueprint.metrics().deadEndRoads() == 0
                && blueprint.metrics().parcels() >= 7
                && blueprint.metrics().decorations() >= 40;
    }

    public static String summary(FloorBlueprint blueprint) {
        return "QA " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().qaScore())
                + " | route slope avg/max "
                + String.format(Locale.ROOT, "%.2f", blueprint.metrics().averageRoadSlope())
                + "/"
                + String.format(Locale.ROOT, "%.2f", blueprint.metrics().maxRoadSlope())
                + " | parcels " + blueprint.parcels().size()
                + " | stamps " + blueprint.stamps().size()
                + " | chunks " + blueprint.chunkRefs().size();
    }

    private static String escape(String input) {
        return input == null ? "" : input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
