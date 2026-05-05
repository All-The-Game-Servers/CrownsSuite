package com.xkstudios.crowns.terrain;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FloorBlueprintArtifactStore {
    private static final int FORMAT = 1;

    private FloorBlueprintArtifactStore() {
    }

    public static File directory(File dataFolder, int floor, String worldName, String profileVersion) {
        return new File(dataFolder, "blueprints/floor-" + floor + "/" + safe(worldName) + "/" + safe(profileVersion));
    }

    public static FloorBlueprint load(File directory) throws IOException {
        File file = new File(directory, "floor.bpbin");
        if (!file.isFile()) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            String magic = input.readUTF();
            if (!"CTBP".equals(magic)) {
                throw new IOException("Invalid CrownsTerrain blueprint magic.");
            }
            int format = input.readInt();
            if (format != FORMAT) {
                throw new IOException("Unsupported blueprint format " + format + ".");
            }
            int floor = input.readInt();
            String worldName = input.readUTF();
            String profileVersion = input.readUTF();
            long seed = input.readLong();
            List<FloorBlueprint.Node> nodes = readNodes(input);
            Map<String, FloorBlueprint.Node> nodeMap = new LinkedHashMap<>();
            for (FloorBlueprint.Node node : nodes) {
                nodeMap.put(node.key(), node);
            }
            List<FloorBlueprint.Road> roads = readRoads(input, nodeMap);
            List<FloorBlueprint.MacroCell> macroCells = readMacroCells(input);
            List<FloorBlueprint.Parcel> parcels = readParcels(input);
            List<FloorBlueprint.Stamp> stamps = readStamps(input);
            List<FloorBlueprint.Decoration> decorations = readDecorations(input);
            List<FloorBlueprint.ChunkRef> chunkRefs = readChunkRefs(input);
            FloorBlueprint.Metrics metrics = readMetrics(input);
            return new FloorBlueprint(floor, worldName, profileVersion, seed, nodes, roads, macroCells, parcels, stamps, decorations, chunkRefs, metrics);
        }
    }

    public static void save(File directory, FloorBlueprint blueprint) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create blueprint artifact directory " + directory.getAbsolutePath());
        }
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(new File(directory, "floor.bpbin")))) {
            output.writeUTF("CTBP");
            output.writeInt(FORMAT);
            output.writeInt(blueprint.floor());
            output.writeUTF(blueprint.worldName());
            output.writeUTF(blueprint.profileVersion());
            output.writeLong(blueprint.seed());
            writeNodes(output, blueprint.nodes());
            writeRoads(output, blueprint.roads());
            writeMacroCells(output, blueprint.macroCells());
            writeParcels(output, blueprint.parcels());
            writeStamps(output, blueprint.stamps());
            writeDecorations(output, blueprint.decorations());
            writeChunkRefs(output, blueprint.chunkRefs());
            writeMetrics(output, blueprint.metrics());
        }
        Files.writeString(new File(directory, "floor.index.json").toPath(), indexJson(blueprint), StandardCharsets.UTF_8);
        BlueprintScoreReport.write(directory, blueprint);
    }

    private static List<FloorBlueprint.Node> readNodes(DataInputStream input) throws IOException {
        List<FloorBlueprint.Node> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.Node(input.readUTF(), input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readUTF()));
        }
        return list;
    }

    private static List<FloorBlueprint.Road> readRoads(DataInputStream input, Map<String, FloorBlueprint.Node> nodeMap) throws IOException {
        List<FloorBlueprint.Road> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            String from = input.readUTF();
            String to = input.readUTF();
            int width = input.readInt();
            String key = input.readUTF();
            FloorBlueprint.Node fromNode = nodeMap.get(from);
            FloorBlueprint.Node toNode = nodeMap.get(to);
            if (fromNode != null && toNode != null) {
                list.add(new FloorBlueprint.Road(fromNode, toNode, width, key));
            }
        }
        return list;
    }

    private static List<FloorBlueprint.MacroCell> readMacroCells(DataInputStream input) throws IOException {
        List<FloorBlueprint.MacroCell> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.MacroCell(input.readInt(), input.readInt(), input.readInt(), input.readDouble(), input.readDouble(), input.readBoolean(), input.readUTF()));
        }
        return list;
    }

    private static List<FloorBlueprint.Parcel> readParcels(DataInputStream input) throws IOException {
        List<FloorBlueprint.Parcel> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.Parcel(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readUTF()));
        }
        return list;
    }

    private static List<FloorBlueprint.Stamp> readStamps(DataInputStream input) throws IOException {
        List<FloorBlueprint.Stamp> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.Stamp(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readUTF()));
        }
        return list;
    }

    private static List<FloorBlueprint.Decoration> readDecorations(DataInputStream input) throws IOException {
        List<FloorBlueprint.Decoration> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.Decoration(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readInt()));
        }
        return list;
    }

    private static List<FloorBlueprint.ChunkRef> readChunkRefs(DataInputStream input) throws IOException {
        List<FloorBlueprint.ChunkRef> list = new ArrayList<>();
        int size = input.readInt();
        for (int i = 0; i < size; i++) {
            list.add(new FloorBlueprint.ChunkRef(input.readInt(), input.readInt(), input.readBoolean(), input.readInt(), input.readInt(), input.readInt(), input.readInt()));
        }
        return list;
    }

    private static FloorBlueprint.Metrics readMetrics(DataInputStream input) throws IOException {
        return new FloorBlueprint.Metrics(input.readDouble(), input.readDouble(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readDouble());
    }

    private static void writeNodes(DataOutputStream output, List<FloorBlueprint.Node> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.Node node : list) {
            output.writeUTF(node.type());
            output.writeUTF(node.key());
            output.writeUTF(node.displayName());
            output.writeInt(node.x());
            output.writeInt(node.y());
            output.writeInt(node.z());
            output.writeInt(node.flattenRadius());
            output.writeUTF(node.role());
        }
    }

    private static void writeRoads(DataOutputStream output, List<FloorBlueprint.Road> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.Road road : list) {
            output.writeUTF(road.from().key());
            output.writeUTF(road.to().key());
            output.writeInt(road.width());
            output.writeUTF(road.key());
        }
    }

    private static void writeMacroCells(DataOutputStream output, List<FloorBlueprint.MacroCell> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.MacroCell cell : list) {
            output.writeInt(cell.x());
            output.writeInt(cell.z());
            output.writeInt(cell.height());
            output.writeDouble(cell.moisture());
            output.writeDouble(cell.slope());
            output.writeBoolean(cell.river());
            output.writeUTF(cell.biome());
        }
    }

    private static void writeParcels(DataOutputStream output, List<FloorBlueprint.Parcel> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.Parcel parcel : list) {
            output.writeUTF(parcel.key());
            output.writeUTF(parcel.district());
            output.writeInt(parcel.minX());
            output.writeInt(parcel.minZ());
            output.writeInt(parcel.maxX());
            output.writeInt(parcel.maxZ());
            output.writeInt(parcel.y());
            output.writeUTF(parcel.role());
        }
    }

    private static void writeStamps(DataOutputStream output, List<FloorBlueprint.Stamp> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.Stamp stamp : list) {
            output.writeUTF(stamp.key());
            output.writeUTF(stamp.type());
            output.writeInt(stamp.x());
            output.writeInt(stamp.y());
            output.writeInt(stamp.z());
            output.writeInt(stamp.width());
            output.writeInt(stamp.depth());
            output.writeUTF(stamp.role());
        }
    }

    private static void writeDecorations(DataOutputStream output, List<FloorBlueprint.Decoration> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.Decoration decoration : list) {
            output.writeUTF(decoration.key());
            output.writeUTF(decoration.type());
            output.writeInt(decoration.x());
            output.writeInt(decoration.z());
            output.writeInt(decoration.radius());
        }
    }

    private static void writeChunkRefs(DataOutputStream output, List<FloorBlueprint.ChunkRef> list) throws IOException {
        output.writeInt(list.size());
        for (FloorBlueprint.ChunkRef ref : list) {
            output.writeInt(ref.chunkX());
            output.writeInt(ref.chunkZ());
            output.writeBoolean(ref.critical());
            output.writeInt(ref.roadRefs());
            output.writeInt(ref.parcelRefs());
            output.writeInt(ref.stampRefs());
            output.writeInt(ref.decorationRefs());
        }
    }

    private static void writeMetrics(DataOutputStream output, FloorBlueprint.Metrics metrics) throws IOException {
        output.writeDouble(metrics.averageRoadSlope());
        output.writeDouble(metrics.maxRoadSlope());
        output.writeInt(metrics.deadEndRoads());
        output.writeInt(metrics.biomeSamples());
        output.writeInt(metrics.parcels());
        output.writeInt(metrics.decorations());
        output.writeInt(metrics.landmarks());
        output.writeDouble(metrics.qaScore());
    }

    private static String indexJson(FloorBlueprint blueprint) {
        return """
                {
                  "floor": %d,
                  "world": "%s",
                  "version": "%s",
                  "seed": %d,
                  "hash": %d,
                  "nodes": %d,
                  "roads": %d,
                  "macroCells": %d,
                  "parcels": %d,
                  "stamps": %d,
                  "decorations": %d,
                  "chunkRefs": %d
                }
                """.formatted(
                blueprint.floor(),
                escape(blueprint.worldName()),
                escape(blueprint.profileVersion()),
                blueprint.seed(),
                blueprint.hash(),
                blueprint.nodes().size(),
                blueprint.roads().size(),
                blueprint.macroCells().size(),
                blueprint.parcels().size(),
                blueprint.stamps().size(),
                blueprint.decorations().size(),
                blueprint.chunkRefs().size()
        );
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String escape(String input) {
        return input == null ? "" : input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
