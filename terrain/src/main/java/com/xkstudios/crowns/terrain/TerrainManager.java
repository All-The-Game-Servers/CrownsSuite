package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.FloorRuntimeProvider;
import com.xkstudios.crowns.api.FloorRuntimeSnapshot;
import com.xkstudios.crowns.api.TerrainPoint;
import com.xkstudios.crowns.api.TerrainProvider;
import com.xkstudios.crowns.data.DataManager;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.WorldCreator;

public class TerrainManager implements TerrainProvider, FloorRuntimeProvider {
    private final CrownsTerrainPlugin plugin;
    private final DataManager dataManager;
    private final StructureTemplateManager structureTemplateManager;
    private final Map<String, TerrainPoint> pointCache = new ConcurrentHashMap<>();
    private final Map<String, FloorBlueprint> blueprintCache = new ConcurrentHashMap<>();
    private final Map<Integer, FloorGenerationJob> activeGenerationJobs = new ConcurrentHashMap<>();

    public TerrainManager(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = CrownsAPI.getDataManager();
        this.structureTemplateManager = new StructureTemplateManager(plugin);
    }

    public void initialize() {
        this.ensureTables();
        this.structureTemplateManager.load();
    }

    @Override
    public ChunkGenerator getGeneratorForFloor(int floorNumber, String worldName, World.Environment environment, long resourceTier) {
        if (!this.plugin.getConfig().getBoolean("terrain.enabled", true)) {
            return null;
        }
        if (this.isHybridBlueprintFloor(floorNumber)) {
            FloorBlueprint blueprint = this.getOrCreateBlueprint(floorNumber);
            boolean caves = this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".vanilla.caves", true);
            boolean mobs = this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".vanilla.mobs", true);
            boolean decorations = this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".vanilla.decorations", false);
            boolean structures = this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".vanilla.structures", false);
            return new BlueprintTerrainGenerator(blueprint, caves, mobs, decorations, structures);
        }
        if (this.isSetMapFloor(floorNumber)) {
            return new SetMapTerrainGenerator(floorNumber, worldName);
        }
        boolean affectAll = this.plugin.getConfig().getBoolean("terrain.affect-all-floors", true);
        int showcase = this.plugin.getConfig().getInt("terrain.showcase-floor", 2);
        if (!affectAll && floorNumber != showcase) {
            return null;
        }
        TerrainTheme theme = this.theme(floorNumber);
        List<TerrainPoint> villages = this.getVillages(floorNumber, worldName);
        TerrainPoint arena = this.getBossArena(floorNumber, worldName);
        List<TerrainPoint> landmarks = this.getLandmarks(floorNumber, worldName);
        List<TerrainPoint> livingPoints = this.getLivingPoints(floorNumber, worldName);
        return new FloorTerrainGenerator(
                floorNumber,
                worldName,
                theme,
                villages,
                arena,
                landmarks,
                livingPoints,
                FloorStructurePlanner.plan(floorNumber, worldName, theme, this.structureTemplateManager, villages, arena, landmarks, livingPoints)
        );
    }

    @Override
    public TerrainPoint getBossArena(int floorNumber, String worldName) {
        if (this.isHybridBlueprintFloor(floorNumber)) {
            return this.getBlueprintPoints(floorNumber, worldName, "arena").stream().findFirst().orElse(null);
        }
        return this.getOrCreatePoint(TerrainLayout.defaultArena(floorNumber, worldName, this.floorSection(floorNumber)));
    }

    @Override
    public List<TerrainPoint> getVillages(int floorNumber, String worldName) {
        if (this.isHybridBlueprintFloor(floorNumber)) {
            return this.getBlueprintPoints(floorNumber, worldName, "village");
        }
        List<TerrainPoint> points = new ArrayList<>();
        for (TerrainPoint point : TerrainLayout.defaultVillages(floorNumber, worldName, this.floorSection(floorNumber))) {
            points.add(this.getOrCreatePoint(point));
        }
        return points;
    }

    @Override
    public List<TerrainPoint> getLandmarks(int floorNumber, String worldName) {
        if (this.isHybridBlueprintFloor(floorNumber)) {
            return this.getBlueprintPoints(floorNumber, worldName, "landmark");
        }
        List<TerrainPoint> points = new ArrayList<>();
        for (TerrainPoint point : TerrainLayout.defaultLandmarks(floorNumber, worldName, this.floorSection(floorNumber))) {
            points.add(this.getOrCreatePoint(point));
        }
        return points;
    }

    public List<TerrainPoint> getLivingPoints(int floorNumber, String worldName) {
        List<TerrainPoint> points = new ArrayList<>();
        for (String type : List.of("camp", "waystone", "road_marker", "shrine")) {
            points.addAll(this.getPoints(floorNumber, worldName, type));
        }
        return points;
    }

    @Override
    public List<TerrainPoint> getPoints(int floorNumber, String worldName, String type) {
        if (type == null || type.isBlank()) {
            return List.of();
        }
        String normalized = normalizeType(type);
        if (!this.isStructureEnabled(floorNumber, normalized)) {
            return List.of();
        }
        if (this.isHybridBlueprintFloor(floorNumber)) {
            return this.getBlueprintPoints(floorNumber, worldName, normalized);
        }
        List<TerrainPoint> points = new ArrayList<>();
        if (normalized.equals("village")) {
            return this.getVillages(floorNumber, worldName);
        }
        if (normalized.equals("landmark")) {
            return this.getLandmarks(floorNumber, worldName);
        }
        if (normalized.equals("arena")) {
            TerrainPoint arena = this.getBossArena(floorNumber, worldName);
            return arena == null ? List.of() : List.of(arena);
        }
        for (TerrainPoint point : TerrainLayout.defaultTypedPoints(floorNumber, worldName, this.floorSection(floorNumber), normalized)) {
            points.add(this.getOrCreatePoint(point));
        }
        return points;
    }

    @Override
    public List<TerrainPoint> getAllPoints(int floorNumber, String worldName) {
        List<TerrainPoint> points = new ArrayList<>();
        for (String type : List.of("village", "camp", "landmark", "waystone", "road_marker", "shrine", "arena")) {
            points.addAll(this.getPoints(floorNumber, worldName, type));
        }
        return points;
    }

    @Override
    public String getFloorTheme(int floorNumber) {
        return this.theme(floorNumber).name();
    }

    public int getWorldSize(int floorNumber) {
        ConfigurationSection section = this.floorSection(floorNumber);
        if (section != null && section.isSet("world-size")) {
            return section.getInt("world-size", floorNumber == 1 ? 16000 : 8000);
        }
        return floorNumber == 1
                ? this.plugin.getConfig().getInt("terrain.defaults.floor-1-world-size", 16000)
                : this.plugin.getConfig().getInt("terrain.defaults.generated-floor-world-size", 8000);
    }

    public String getWorldName(int floorNumber) {
        ConfigurationSection section = this.floorSection(floorNumber);
        if (section != null && section.isSet("world")) {
            return section.getString("world", floorNumber == 1 ? "crowns_floor_1_v6" : "crowns_floor_" + floorNumber);
        }
        if (floorNumber == 1) {
            return this.plugin.getConfig().getString("terrain.floor-1-world", "crowns_floor_1_v6");
        }
        return this.plugin.getConfig().getString("terrain.generated-world-prefix", "crowns_floor_") + floorNumber;
    }

    public int floorForWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return 0;
        }
        for (int floor = 1; floor <= 64; floor++) {
            ConfigurationSection section = this.floorSection(floor);
            if (section == null && floor > 3) {
                break;
            }
            if (this.getWorldName(floor).equalsIgnoreCase(worldName)) {
                return floor;
            }
        }
        String prefix = this.plugin.getConfig().getString("terrain.generated-world-prefix", "crowns_floor_");
        if (worldName.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            try {
                return Integer.parseInt(worldName.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public World createFloorWorld(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generator(this.getGeneratorForFloor(floorNumber, worldName, World.Environment.NORMAL, floorNumber))
                .generateStructures(!this.isHybridBlueprintFloor(floorNumber));
        if (this.isHybridBlueprintFloor(floorNumber)) {
            creator.biomeProvider(new BlueprintBiomeProvider(this.getOrCreateBlueprint(floorNumber)));
        }
        World world = creator.createWorld();
        if (world != null) {
            world.getWorldBorder().setCenter(0.0D, 0.0D);
            world.getWorldBorder().setSize(Math.max(1, this.getWorldSize(floorNumber)));
            this.getAllPoints(floorNumber, worldName);
        }
        return world;
    }

    public boolean startGeneration(CommandSender sender, int floorNumber) {
        if (!this.isManagedGenerationFloor(floorNumber)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Floor " + floorNumber + " is not configured for managed CrownsTerrain generation.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        if (this.isHybridBlueprintFloor(floorNumber)) {
            this.getOrCreateBlueprint(floorNumber);
        }
        if (this.activeGenerationJobs.containsKey(floorNumber)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Floor " + floorNumber + " is already generating. Use /cterrain admin status " + floorNumber + ".", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            return false;
        }
        FloorGenerationJob job = new FloorGenerationJob(this.plugin, this, floorNumber, sender);
        this.activeGenerationJobs.put(floorNumber, job);
        job.start();
        return true;
    }

    public boolean cancelGeneration(CommandSender sender, int floorNumber) {
        FloorGenerationJob job = this.activeGenerationJobs.remove(floorNumber);
        if (job == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Floor " + floorNumber + " has no active generation job.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        job.cancel("Cancelled by " + sender.getName() + ".");
        sender.sendMessage(net.kyori.adventure.text.Component.text("Cancelled CrownsTerrain generation for Floor " + floorNumber + ".", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        return true;
    }

    public void clearActiveGeneration(int floorNumber) {
        this.activeGenerationJobs.remove(floorNumber);
    }

    @Override
    public boolean isFloorReadyForPlayers(int floorNumber) {
        if (!this.isManagedGenerationFloor(floorNumber)) {
            return true;
        }
        return this.getGenerationStatus(floorNumber).readyForPlayers();
    }

    @Override
    public String getFloorReadinessSummary(int floorNumber) {
        if (!this.isManagedGenerationFloor(floorNumber)) {
            return "Floor " + floorNumber + " is unmanaged by CrownsTerrain and treated as ready.";
        }
        TerrainGenerationStatus status = this.getGenerationStatus(floorNumber);
        String message = status.message() == null || status.message().isBlank() ? "" : " " + status.message();
        return "Floor " + floorNumber + " " + status.status().toLowerCase(Locale.ROOT).replace('_', '-') + " in "
                + status.worldName() + ": " + status.progressSummary() + "." + message;
    }

    @Override
    public FloorRuntimeSnapshot getFloorRuntime(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        TerrainGenerationStatus status = this.getGenerationStatus(floorNumber);
        List<TerrainPoint> anchors = this.runtimeAnchors(floorNumber, worldName);
        List<String> qaLines = this.isManagedGenerationFloor(floorNumber) ? this.verifyFloor(floorNumber) : List.of("PASS: unmanaged floor treated as ready.");
        boolean qaPasses = qaLines.stream().noneMatch(line -> line.startsWith("FAIL"));
        boolean safeReady = status.readyForPlayers() && qaPasses && this.hasRequiredRuntimeAnchors(anchors);
        String summary = this.getFloorReadinessSummary(floorNumber);
        return new FloorRuntimeSnapshot(
                floorNumber,
                worldName,
                this.isHybridBlueprintFloor(floorNumber) ? this.getBlueprintVersion(floorNumber) : this.getTerrainProfile(floorNumber),
                safeReady ? "SAFE_READY" : status.status(),
                status.readyForPlayers(),
                safeReady,
                summary,
                this.runtimeRepairSteps(floorNumber),
                anchors,
                qaLines
        );
    }

    private List<TerrainPoint> runtimeAnchors(int floorNumber, String worldName) {
        List<TerrainPoint> anchors = new ArrayList<>();
        anchors.addAll(this.getPoints(floorNumber, worldName, "village"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "landmark"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "road_marker"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "camp"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "shrine"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "waystone"));
        anchors.addAll(this.getPoints(floorNumber, worldName, "arena"));
        return anchors;
    }

    private boolean hasRequiredRuntimeAnchors(List<TerrainPoint> anchors) {
        return this.hasAnchor(anchors, "first-haven", "village")
                && this.hasAnchor(anchors, "starter-camp", "camp")
                && this.hasAnchor(anchors, "starter-shrine", "shrine")
                && this.hasAnchor(anchors, "first-waystone", "waystone")
                && this.hasAnchor(anchors, "first-gate-arena", "arena");
    }

    private boolean hasAnchor(List<TerrainPoint> anchors, String preferredKey, String fallbackType) {
        for (TerrainPoint anchor : anchors) {
            if (anchor.key().equalsIgnoreCase(preferredKey) || normalizeType(anchor.type()).equals(normalizeType(fallbackType))) {
                return true;
            }
        }
        return false;
    }

    private List<String> runtimeRepairSteps(int floorNumber) {
        if (!this.isManagedGenerationFloor(floorNumber)) {
            return List.of("This floor is not managed by CrownsTerrain. Check its normal world configuration.");
        }
        return List.of(
                "/cterrain admin blueprint " + floorNumber,
                "/cterrain admin debugmaps " + floorNumber,
                "/cterrain floor pregenerate " + floorNumber + " critical",
                "/cterrain floor qa " + floorNumber,
                "/cterrain verify floor " + floorNumber
        );
    }

    public boolean isManagedGenerationFloor(int floorNumber) {
        return this.isSetMapFloor(floorNumber) || this.isHybridBlueprintFloor(floorNumber);
    }

    public boolean isSetMapFloor(int floorNumber) {
        return this.plugin.getConfig().getString("terrain.floors." + floorNumber + ".generation-mode", "").equalsIgnoreCase("set-map");
    }

    public boolean isHybridBlueprintFloor(int floorNumber) {
        String mode = this.plugin.getConfig().getString("terrain.floors." + floorNumber + ".generation-mode", "");
        return mode.equalsIgnoreCase("hybrid-blueprint") || mode.equalsIgnoreCase("hybrid-engine");
    }

    public FloorBlueprint getOrCreateBlueprint(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        String profileVersion = this.getBlueprintVersion(floorNumber);
        long seed = this.getBlueprintSeed(floorNumber, worldName);
        String key = floorNumber + ":" + worldName.toLowerCase(Locale.ROOT) + ":" + profileVersion + ":" + seed;
        FloorBlueprint cached = this.blueprintCache.get(key);
        if (cached != null) {
            return cached;
        }
        File artifactDir = FloorBlueprintArtifactStore.directory(this.plugin.getDataFolder(), floorNumber, worldName, profileVersion);
        FloorBlueprint blueprint = null;
        try {
            blueprint = FloorBlueprintArtifactStore.load(artifactDir);
        } catch (IOException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not load blueprint artifact; rebuilding: " + exception.getMessage());
        }
        if (blueprint == null || blueprint.seed() != seed || !blueprint.worldName().equals(worldName) || !blueprint.profileVersion().equals(profileVersion)) {
            blueprint = FloorBlueprintFactory.create(floorNumber, worldName, profileVersion, seed);
            try {
                FloorBlueprintArtifactStore.save(artifactDir, blueprint);
            } catch (IOException exception) {
                this.plugin.getLogger().warning("[CrownsTerrain] Could not save blueprint artifact: " + exception.getMessage());
            }
        }
        this.blueprintCache.put(key, blueprint);
        this.saveBlueprintMetadataIfMissing(blueprint);
        for (FloorBlueprint.Node node : blueprint.nodes()) {
            this.getOrCreatePoint(node.toPoint(floorNumber, worldName));
        }
        return blueprint;
    }

    public void prepareBlueprint(CommandSender sender, int floorNumber) {
        if (!this.isHybridBlueprintFloor(floorNumber)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Floor " + floorNumber + " is not configured for hybrid-engine generation.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        FloorBlueprint blueprint = this.getOrCreateBlueprint(floorNumber);
        File artifactDir = FloorBlueprintArtifactStore.directory(this.plugin.getDataFolder(), floorNumber, blueprint.worldName(), blueprint.profileVersion());
        sender.sendMessage(net.kyori.adventure.text.Component.text("Prepared Floor " + floorNumber + " hybrid-engine blueprint '" + blueprint.profileVersion() + "' for " + blueprint.worldName() + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        sender.sendMessage(net.kyori.adventure.text.Component.text("Hash: " + blueprint.hash() + " | QA: " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().qaScore())
                + " | Nodes: " + blueprint.nodes().size() + " | Roads: " + blueprint.roads().size() + " | Parcels: " + blueprint.parcels().size(), net.kyori.adventure.text.format.NamedTextColor.GRAY));
        sender.sendMessage(net.kyori.adventure.text.Component.text("Artifact: " + artifactDir.getAbsolutePath(), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    }

    public void generateDebugMaps(CommandSender sender, int floorNumber) {
        if (!this.isHybridBlueprintFloor(floorNumber)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Floor " + floorNumber + " is not configured for hybrid-engine debug maps.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".debug-maps.enabled", true)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Debug maps are disabled for Floor " + floorNumber + ".", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        FloorBlueprint blueprint = this.getOrCreateBlueprint(floorNumber);
        File outputDir = new File(FloorBlueprintArtifactStore.directory(this.plugin.getDataFolder(), floorNumber, blueprint.worldName(), blueprint.profileVersion()), "debug-maps");
        try {
            List<File> files = BlueprintDebugRenderer.render(outputDir, blueprint);
            this.saveBlueprintMetadata(blueprint, "SCORES_READY", outputDir.getPath(), "Debug maps and scores rendered: " + files.size());
            sender.sendMessage(net.kyori.adventure.text.Component.text("Rendered " + files.size() + " Floor " + floorNumber + " blueprint debug artifacts.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
            sender.sendMessage(net.kyori.adventure.text.Component.text("Path: " + outputDir.getAbsolutePath(), net.kyori.adventure.text.format.NamedTextColor.GRAY));
            sender.sendMessage(net.kyori.adventure.text.Component.text(BlueprintScoreReport.summary(blueprint), net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
        } catch (IOException exception) {
            this.saveBlueprintMetadata(blueprint, "DEBUG_FAILED", outputDir.getPath(), exception.getMessage());
            sender.sendMessage(net.kyori.adventure.text.Component.text("Could not render debug maps: " + exception.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    public List<String> getBlueprintStatusLines(int floorNumber) {
        List<String> lines = new ArrayList<>();
        if (!this.isHybridBlueprintFloor(floorNumber)) {
            lines.add("Blueprint: not configured for this floor.");
            return lines;
        }
        FloorBlueprint blueprint = this.getOrCreateBlueprint(floorNumber);
        File artifactDir = FloorBlueprintArtifactStore.directory(this.plugin.getDataFolder(), floorNumber, blueprint.worldName(), blueprint.profileVersion());
        lines.add("Blueprint: " + blueprint.profileVersion() + " | hash " + blueprint.hash());
        lines.add("Artifact: " + artifactDir.getPath()
                + " | bpbin " + (new File(artifactDir, "floor.bpbin").exists() ? "yes" : "missing")
                + " | scores " + (new File(artifactDir, "scores.json").exists() ? "yes" : "missing"));
        lines.add("Seed: " + blueprint.seed() + " | QA " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().qaScore()));
        lines.add("Metrics: avg road slope " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().averageRoadSlope())
                + " | max " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().maxRoadSlope())
                + " | parcels " + blueprint.metrics().parcels()
                + " | decorations " + blueprint.metrics().decorations());
        lines.add("Intent: " + blueprint.nodes().size() + " nodes, " + blueprint.roads().size() + " roads, " + blueprint.parcels().size()
                + " parcels, " + blueprint.stamps().size() + " stamps, " + blueprint.chunkRefs().size() + " indexed chunks.");
        lines.add("Scores: " + BlueprintScoreReport.summary(blueprint));
        if (this.dataManager != null) {
            try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                    SELECT status, debug_path, message FROM terrain_blueprints
                    WHERE floor_number = ? AND world_name = ? AND profile_version = ?
                    """)) {
                statement.setInt(1, floorNumber);
                statement.setString(2, blueprint.worldName());
                statement.setString(3, blueprint.profileVersion());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        lines.add("Stored status: " + result.getString("status"));
                        String path = result.getString("debug_path");
                        if (path != null && !path.isBlank()) {
                            lines.add("Debug maps: " + path);
                        }
                        String message = result.getString("message");
                        if (message != null && !message.isBlank()) {
                            lines.add("Blueprint message: " + message);
                        }
                    }
                }
            } catch (SQLException exception) {
                lines.add("Blueprint status load failed: " + exception.getMessage());
            }
        }
        return lines;
    }

    public TerrainGenerationStatus getGenerationStatus(int floorNumber) {
        FloorGenerationJob active = this.activeGenerationJobs.get(floorNumber);
        if (active != null) {
            return active.snapshot();
        }
        String worldName = this.getWorldName(floorNumber);
        if (this.dataManager == null) {
            return TerrainGenerationStatus.notGenerated(floorNumber, worldName, this.isHybridBlueprintFloor(floorNumber) ? this.getBlueprintVersion(floorNumber) : this.getTerrainProfile(floorNumber));
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT profile_version, status, total_chunks, generated_chunks, total_blocks, placed_blocks, started_at, completed_at, started_by, message
                FROM terrain_generation_jobs
                WHERE floor_number = ? AND world_name = ?
                """)) {
            statement.setInt(1, floorNumber);
            statement.setString(2, worldName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new TerrainGenerationStatus(
                            floorNumber,
                            worldName,
                            result.getString("profile_version"),
                            result.getString("status"),
                            result.getInt("total_chunks"),
                            result.getInt("generated_chunks"),
                            result.getInt("total_blocks"),
                            result.getInt("placed_blocks"),
                            result.getLong("started_at"),
                            result.getLong("completed_at"),
                            result.getString("started_by"),
                            result.getString("message")
                    );
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not load generation status: " + exception.getMessage());
        }
        return TerrainGenerationStatus.notGenerated(floorNumber, worldName, this.isHybridBlueprintFloor(floorNumber) ? this.getBlueprintVersion(floorNumber) : this.getTerrainProfile(floorNumber));
    }

    public List<String> getGenerationStatusLines(int floorNumber) {
        TerrainGenerationStatus status = this.getGenerationStatus(floorNumber);
        List<String> lines = new ArrayList<>();
        lines.add("Floor " + floorNumber + " world: " + status.worldName());
        if (this.isHybridBlueprintFloor(floorNumber)) {
            lines.addAll(this.getBlueprintStatusLines(floorNumber));
        }
        lines.add("Status: " + status.status() + " | " + status.progressSummary());
        if (status.startedBy() != null && !status.startedBy().isBlank()) {
            lines.add("Started by: " + status.startedBy());
        }
        if (status.message() != null && !status.message().isBlank()) {
            lines.add("Message: " + status.message());
        }
        return lines;
    }

    public void saveGenerationJob(int floorNumber, String worldName, String status, int totalChunks, int generatedChunks,
                                  int totalBlocks, int placedBlocks, String startedBy, String message) {
        if (this.dataManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO terrain_generation_jobs
                (floor_number, world_name, profile_version, status, total_chunks, generated_chunks, total_blocks, placed_blocks, started_at, completed_at, started_by, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT started_at FROM terrain_generation_jobs WHERE floor_number = ? AND world_name = ?), ?), COALESCE((SELECT completed_at FROM terrain_generation_jobs WHERE floor_number = ? AND world_name = ?), 0), ?, ?)
                """)) {
            statement.setInt(1, floorNumber);
            statement.setString(2, worldName);
            statement.setString(3, this.isHybridBlueprintFloor(floorNumber) ? this.getBlueprintVersion(floorNumber) : this.getTerrainProfile(floorNumber));
            statement.setString(4, status);
            statement.setInt(5, totalChunks);
            statement.setInt(6, generatedChunks);
            statement.setInt(7, totalBlocks);
            statement.setInt(8, placedBlocks);
            statement.setInt(9, floorNumber);
            statement.setString(10, worldName);
            statement.setLong(11, now);
            statement.setInt(12, floorNumber);
            statement.setString(13, worldName);
            statement.setString(14, startedBy == null ? "" : startedBy);
            statement.setString(15, message == null ? "" : message);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not save generation status: " + exception.getMessage());
        }
    }

    public void finishGenerationJob(int floorNumber, String status, int totalChunks, int generatedChunks,
                                    int totalBlocks, int placedBlocks, String startedBy, String message) {
        if (this.dataManager == null) {
            return;
        }
        String worldName = this.getWorldName(floorNumber);
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO terrain_generation_jobs
                (floor_number, world_name, profile_version, status, total_chunks, generated_chunks, total_blocks, placed_blocks, started_at, completed_at, started_by, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT started_at FROM terrain_generation_jobs WHERE floor_number = ? AND world_name = ?), ?), ?, ?, ?)
                """)) {
            statement.setInt(1, floorNumber);
            statement.setString(2, worldName);
            statement.setString(3, this.isHybridBlueprintFloor(floorNumber) ? this.getBlueprintVersion(floorNumber) : this.getTerrainProfile(floorNumber));
            statement.setString(4, status);
            statement.setInt(5, totalChunks);
            statement.setInt(6, generatedChunks);
            statement.setInt(7, totalBlocks);
            statement.setInt(8, placedBlocks);
            statement.setInt(9, floorNumber);
            statement.setString(10, worldName);
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.setString(13, startedBy == null ? "" : startedBy);
            statement.setString(14, message == null ? "" : message);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not finish generation status: " + exception.getMessage());
        }
    }

    public List<String> verifyFloor(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        List<String> lines = new ArrayList<>();
        World world = Bukkit.getWorld(worldName);
        lines.add("World: " + worldName + (world == null ? " (not loaded)" : " (loaded)"));
        lines.add("Profile: " + this.getTerrainProfile(floorNumber) + " | Size: " + this.getWorldSize(floorNumber));
        lines.add("Expected world: " + worldName + (floorNumber == 1 && this.isFreshWorldRequired(floorNumber) ? " | fresh world required" : ""));
        lines.add("Bundled templates loaded: " + this.structureTemplateManager.count());
        TerrainGenerationStatus generationStatus = this.getGenerationStatus(floorNumber);
        if (this.isManagedGenerationFloor(floorNumber)) {
            lines.add("Generation: " + generationStatus.status() + " | " + generationStatus.progressSummary());
        }
        FloorBlueprint blueprint = null;
        if (this.isHybridBlueprintFloor(floorNumber)) {
            blueprint = this.getOrCreateBlueprint(floorNumber);
            double minimumQa = this.plugin.getConfig().getDouble("terrain.floors." + floorNumber + ".qa.minimum-qa-score", 0.70D);
            File artifactDir = FloorBlueprintArtifactStore.directory(this.plugin.getDataFolder(), floorNumber, blueprint.worldName(), blueprint.profileVersion());
            lines.add("Blueprint: " + blueprint.profileVersion() + " | hash " + blueprint.hash());
            lines.add(new File(artifactDir, "floor.bpbin").exists()
                    ? "PASS: blueprint binary artifact exists."
                    : "FAIL: blueprint binary artifact floor.bpbin is missing.");
            lines.add(new File(artifactDir, "floor.index.json").exists()
                    ? "PASS: blueprint index sidecar exists."
                    : "FAIL: blueprint index sidecar floor.index.json is missing.");
            lines.add(new File(artifactDir, "scores.json").exists()
                    ? "PASS: blueprint score report exists."
                    : "FAIL: blueprint score report scores.json is missing. Run /cterrain admin debugmaps " + floorNumber + ".");
            lines.add("Blueprint QA: " + String.format(Locale.ROOT, "%.2f", blueprint.metrics().qaScore())
                    + " | road slope avg/max "
                    + String.format(Locale.ROOT, "%.2f", blueprint.metrics().averageRoadSlope())
                    + "/"
                    + String.format(Locale.ROOT, "%.2f", blueprint.metrics().maxRoadSlope()));
            lines.add(BlueprintScoreReport.passes(blueprint, minimumQa)
                    ? "PASS: blueprint QA score meets threshold " + String.format(Locale.ROOT, "%.2f", minimumQa) + "."
                    : "FAIL: blueprint QA score is below threshold " + String.format(Locale.ROOT, "%.2f", minimumQa) + ".");
            lines.add(blueprint.roads().isEmpty()
                    ? "FAIL: blueprint route graph has no roads."
                    : "PASS: blueprint route graph has " + blueprint.roads().size() + " roads.");
            lines.add(blueprint.parcels().size() >= this.plugin.getConfig().getInt("terrain.floors." + floorNumber + ".qa.thresholds.minimum-parcels", 7)
                    ? "PASS: blueprint has parcel-based settlement planning."
                    : "FAIL: blueprint has too few parcels for a readable settlement.");
        }
        List<TerrainPoint> villages = this.getVillages(floorNumber, worldName);
        TerrainPoint firstHaven = villages.stream()
                .filter(point -> point.key().equalsIgnoreCase("first-haven") || point.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(villages.isEmpty() ? null : villages.get(0));
        lines.add("Villages: " + villages.size() + (firstHaven == null ? "" : " | First spawn: " + firstHaven.coordinateSummary()));
        lines.add("Persisted points: " + this.countPersistedPoints(floorNumber, worldName));
        if (world == null) {
            lines.add(this.isManagedGenerationFloor(floorNumber)
                    ? "FAIL: world is not loaded. Run /cterrain admin generate " + floorNumber + " first."
                    : "FAIL: world is not loaded. Run /cterrain admin create " + floorNumber + " first.");
            return lines;
        }
        if (firstHaven == null) {
            lines.add("FAIL: no First Haven village point exists.");
            return lines;
        }
        boolean chunkLoaded = world.getChunkAt(firstHaven.x() >> 4, firstHaven.z() >> 4).load(true);
        lines.add("First Haven chunk: " + (chunkLoaded ? "loaded" : "could not load"));
        lines.add(this.hasVillageBlocks(world, firstHaven)
                ? "PASS: First Haven generated blocks are present."
                : "FAIL: First Haven point exists, but generated village blocks were not found nearby.");
        TerrainPoint arena = this.getBossArena(floorNumber, worldName);
        lines.add(arena != null && this.hasArenaBlocks(world, arena)
                ? "PASS: First Gate arena generated blocks are present."
                : "FAIL: First Gate arena generated blocks were not found.");
        lines.add(this.hasPointBlocks(world, this.getPoints(floorNumber, worldName, "camp"), Material.CAMPFIRE)
                ? "PASS: starter camp feature blocks are present."
                : "FAIL: starter camp feature blocks were not found.");
        lines.add(this.hasPointBlocks(world, this.getPoints(floorNumber, worldName, "waystone"), this.theme(floorNumber).accent())
                ? "PASS: waystone feature blocks are present."
                : "FAIL: waystone feature blocks were not found.");
        if (floorNumber == 1 && this.isManagedGenerationFloor(floorNumber)) {
            lines.add(generationStatus.readyForPlayers()
                    ? "PASS: Floor 1 managed route generation is CRITICAL_READY or better."
                    : "FAIL: Floor 1 managed route generation is not ready. Run /cterrain admin generate 1.");
            lines.add(this.hasPointBlocks(world, this.getPoints(floorNumber, worldName, "landmark"), Material.CUT_COPPER)
                    ? "PASS: market square / civic anchor blocks are present."
                    : "FAIL: market square / civic anchor blocks were not found.");
            lines.add(this.hasFarmBlocks(world, this.getPoints(floorNumber, worldName, "road_marker"))
                    ? "PASS: farm district blocks are present."
                    : "FAIL: farm district blocks were not found.");
            lines.add(this.hasPointBlocks(world, this.getPoints(floorNumber, worldName, "shrine"), Material.MOSSY_STONE_BRICKS)
                    ? "PASS: starter shrine blocks are present."
                    : "FAIL: starter shrine blocks were not found.");
            lines.add(this.hasRoadNetwork(world, floorNumber, worldName, firstHaven, arena)
                    ? "PASS: critical-route roads have physical route blocks."
                    : "FAIL: critical-route roads are missing between major points.");
            lines.add(this.hasSetMapHouseBlocks(world, firstHaven)
                    ? "PASS: First Haven has large authored building blocks."
                    : "FAIL: First Haven large authored building blocks were not found.");
            if (blueprint != null) {
                lines.add(blueprint.metrics().decorations() >= this.plugin.getConfig().getInt("terrain.floors." + floorNumber + ".qa.thresholds.minimum-decorations", 40)
                        ? "PASS: blueprint has enough wilderness support decoration candidates."
                        : "FAIL: blueprint has too few wilderness support decoration candidates.");
            }
            lines.add("Debug: if these fail, this world was likely loaded but never pregenerated with /cterrain admin generate 1.");
            return lines;
        }
        if (floorNumber == 1) {
            List<StructurePlacement> plannedPlacements = this.plannedPlacements(floorNumber, worldName);
            int minimumTownStructures = this.plugin.getConfig().getInt("terrain.floors.1.qa.minimum-town-structures", 20);
            int plannedTownStructures = this.countPlannedNear(plannedPlacements, firstHaven, 128);
            int physicalTownStructures = this.countPhysicalPlacements(world, plannedPlacements, firstHaven, 128);
            lines.add(plannedTownStructures >= minimumTownStructures
                    ? "PASS: First Haven has " + plannedTownStructures + " planned authored pieces."
                    : "FAIL: First Haven only has " + plannedTownStructures + " planned authored pieces; expected at least " + minimumTownStructures + ".");
            lines.add(physicalTownStructures >= minimumTownStructures
                    ? "PASS: First Haven has " + physicalTownStructures + " physical authored pieces generated."
                    : "FAIL: First Haven only has " + physicalTownStructures + " physical authored pieces generated; expected at least " + minimumTownStructures + ".");
            lines.add(this.hasPointBlocks(world, List.of(firstHaven), Material.STONE_BRICKS)
                    ? "PASS: First Haven anchors/district foundations are present."
                    : "FAIL: First Haven anchor/district foundations were not found.");
            TerrainPoint gatehouseCheck = arena == null ? null : new TerrainPoint(floorNumber, worldName, "arena", "gatehouse-check", "Gatehouse Check", arena.x(), arena.y(), arena.z() - 58);
            lines.add(gatehouseCheck != null && this.hasPointBlocks(world, List.of(gatehouseCheck), Material.STONE_BRICKS)
                    ? "PASS: gatehouse approach blocks are present."
                    : "FAIL: gatehouse approach blocks were not found.");
            lines.add(this.hasRoadNetwork(world, floorNumber, worldName, firstHaven, arena)
                    ? "PASS: road network has physical route blocks to major points."
                    : "FAIL: road network is missing route blocks to one or more major points.");
            lines.add(this.hasHydrologyBlocks(world, firstHaven)
                    ? "PASS: Floor 1 hydrology blocks are present near the starter region."
                    : "FAIL: Floor 1 hydrology blocks were not found near the starter region.");
            lines.add(this.hasRegionVariety(floorNumber, worldName)
                    ? "PASS: Floor 1 region map exposes meadow, forest/highland, ridge/farm, river, and gate variety."
                    : "FAIL: Floor 1 region map is too uniform; expected at least five region samples.");
            lines.add(this.hasAnyHeroTreeBlock(world, firstHaven)
                    ? "PASS: hero-tree / vertical landmark blocks are present near First Haven."
                    : "FAIL: hero-tree / vertical landmark blocks were not found near First Haven.");
            lines.add("Debug: missing structures usually mean templates are disabled, points are stale from an older world, chunks were generated before 1.5.3, or the server is still using an older floor world.");
        }
        return lines;
    }

    public String getTerrainProfile(int floorNumber) {
        return this.plugin.getConfig().getString("terrain.floors." + floorNumber + ".terrain-profile", floorNumber == 1 ? "living_haven" : "procedural_adventure");
    }

    public double getStructureDensity(int floorNumber) {
        return this.plugin.getConfig().getDouble("terrain.floors." + floorNumber + ".structure-density",
                this.plugin.getConfig().getDouble("terrain.defaults.structure-density", 1.0D));
    }

    public List<String> getRegionNames(int floorNumber) {
        if (floorNumber != 1) {
            return List.of(this.getTerrainProfile(floorNumber));
        }
        return TerrainDesignLanguage.floorOneRegions();
    }

    public List<String> getDistrictNames(int floorNumber) {
        if (floorNumber != 1) {
            return List.of("outpost", "road edge", "arena approach");
        }
        return TerrainDesignLanguage.floorOneDistricts();
    }

    public String getTreePoolSummary(int floorNumber) {
        return floorNumber == 1 ? TerrainDesignLanguage.treePoolSummary() : "floor-themed trees and sparse adventure clutter";
    }

    public String getHydrologySummary(int floorNumber) {
        return floorNumber == 1 ? TerrainDesignLanguage.hydrologySummary() : "floor-themed streams and water features";
    }

    public boolean isFreshWorldRequired(int floorNumber) {
        return this.plugin.getConfig().getBoolean("terrain.floors." + floorNumber + ".fresh-world-required", false);
    }

    public int countPersistedPoints(int floorNumber, String worldName) {
        if (this.dataManager == null) {
            return 0;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM terrain_points WHERE floor_number = ? AND world_name = ?")) {
            statement.setInt(1, floorNumber);
            statement.setString(2, worldName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not count terrain points: " + exception.getMessage());
            return 0;
        }
    }

    public void reload() {
        this.plugin.reloadConfig();
        this.pointCache.clear();
        this.blueprintCache.clear();
        this.structureTemplateManager.load();
    }

    public TerrainTheme theme(int floorNumber) {
        ConfigurationSection section = this.floorSection(floorNumber);
        String configuredName = section == null ? null : section.getString("theme");
        return TerrainTheme.forFloor(floorNumber, configuredName);
    }

    private String getBlueprintVersion(int floorNumber) {
        return this.plugin.getConfig().getString("terrain.floors." + floorNumber + ".blueprint-version", this.getTerrainProfile(floorNumber));
    }

    private long getBlueprintSeed(int floorNumber, String worldName) {
        String path = "terrain.floors." + floorNumber + ".blueprint-seed";
        if (this.plugin.getConfig().isSet(path)) {
            return this.plugin.getConfig().getLong(path);
        }
        return TerrainLayout.layoutSeed(worldName, floorNumber);
    }

    private List<TerrainPoint> getBlueprintPoints(int floorNumber, String worldName, String type) {
        FloorBlueprint blueprint = this.getOrCreateBlueprint(floorNumber);
        String normalized = normalizeType(type);
        List<TerrainPoint> points = new ArrayList<>();
        for (FloorBlueprint.Node node : blueprint.nodes()) {
            if (normalizeType(node.type()).equals(normalized)) {
                points.add(this.getOrCreatePoint(node.toPoint(floorNumber, worldName)));
            }
        }
        return points;
    }

    private void saveBlueprintMetadata(FloorBlueprint blueprint, String status, String debugPath, String message) {
        if (this.dataManager == null) {
            return;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO terrain_blueprints
                (floor_number, world_name, profile_version, seed, blueprint_hash, status, qa_score, generated_at, debug_path, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, blueprint.floor());
            statement.setString(2, blueprint.worldName());
            statement.setString(3, blueprint.profileVersion());
            statement.setLong(4, blueprint.seed());
            statement.setLong(5, blueprint.hash());
            statement.setString(6, status == null ? "BLUEPRINT_READY" : status);
            statement.setDouble(7, blueprint.metrics().qaScore());
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, debugPath == null ? "" : debugPath);
            statement.setString(10, message == null ? "" : message);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not save blueprint metadata: " + exception.getMessage());
        }
    }

    private void saveBlueprintMetadataIfMissing(FloorBlueprint blueprint) {
        if (this.dataManager == null) {
            return;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR IGNORE INTO terrain_blueprints
                (floor_number, world_name, profile_version, seed, blueprint_hash, status, qa_score, generated_at, debug_path, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, blueprint.floor());
            statement.setString(2, blueprint.worldName());
            statement.setString(3, blueprint.profileVersion());
            statement.setLong(4, blueprint.seed());
            statement.setLong(5, blueprint.hash());
            statement.setString(6, "BLUEPRINT_READY");
            statement.setDouble(7, blueprint.metrics().qaScore());
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, "");
            statement.setString(10, "Blueprint precomputed and cached.");
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not save blueprint metadata: " + exception.getMessage());
        }
    }

    private TerrainPoint getOrCreatePoint(TerrainPoint fallback) {
        if (fallback == null) {
            return null;
        }
        String cacheKey = this.cacheKey(fallback.floor(), fallback.worldName(), fallback.type(), fallback.key());
        TerrainPoint cached = this.pointCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        TerrainPoint loaded = this.loadPoint(fallback);
        TerrainPoint resolved = loaded == null ? fallback : loaded;
        if (loaded == null && this.plugin.getConfig().getBoolean("terrain.persist-layout-points", true)) {
            this.savePoint(fallback);
        }
        this.pointCache.put(cacheKey, resolved);
        return resolved;
    }

    private TerrainPoint loadPoint(TerrainPoint fallback) {
        if (this.dataManager == null) {
            return null;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT display_name, x, y, z FROM terrain_points
                WHERE floor_number = ? AND world_name = ? AND point_type = ? AND point_key = ?
                """)) {
            statement.setInt(1, fallback.floor());
            statement.setString(2, fallback.worldName());
            statement.setString(3, fallback.type());
            statement.setString(4, fallback.key());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new TerrainPoint(fallback.floor(), fallback.worldName(), fallback.type(), fallback.key(),
                            result.getString("display_name"), result.getInt("x"), result.getInt("y"), result.getInt("z"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not load terrain point: " + exception.getMessage());
        }
        return null;
    }

    private void savePoint(TerrainPoint point) {
        if (this.dataManager == null) {
            return;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO terrain_points
                (floor_number, world_name, point_type, point_key, display_name, x, y, z, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, point.floor());
            statement.setString(2, point.worldName());
            statement.setString(3, point.type());
            statement.setString(4, point.key());
            statement.setString(5, point.displayName());
            statement.setInt(6, point.x());
            statement.setInt(7, point.y());
            statement.setInt(8, point.z());
            statement.setLong(9, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not save terrain point: " + exception.getMessage());
        }
    }

    private void ensureTables() {
        if (this.dataManager == null) {
            return;
        }
        try (Statement statement = this.dataManager.getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS terrain_points (
                        floor_number INTEGER NOT NULL,
                        world_name TEXT NOT NULL,
                        point_type TEXT NOT NULL,
                        point_key TEXT NOT NULL,
                        display_name TEXT,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY (floor_number, world_name, point_type, point_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS terrain_generation_jobs (
                        floor_number INTEGER NOT NULL,
                        world_name TEXT NOT NULL,
                        profile_version TEXT NOT NULL,
                        status TEXT NOT NULL,
                        total_chunks INTEGER NOT NULL DEFAULT 0,
                        generated_chunks INTEGER NOT NULL DEFAULT 0,
                        total_blocks INTEGER NOT NULL DEFAULT 0,
                        placed_blocks INTEGER NOT NULL DEFAULT 0,
                        started_at INTEGER NOT NULL,
                        completed_at INTEGER NOT NULL DEFAULT 0,
                        started_by TEXT,
                        message TEXT,
                        PRIMARY KEY (floor_number, world_name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS terrain_blueprints (
                        floor_number INTEGER NOT NULL,
                        world_name TEXT NOT NULL,
                        profile_version TEXT NOT NULL,
                        seed INTEGER NOT NULL,
                        blueprint_hash INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        qa_score REAL NOT NULL DEFAULT 0,
                        generated_at INTEGER NOT NULL,
                        debug_path TEXT,
                        message TEXT,
                        PRIMARY KEY (floor_number, world_name, profile_version)
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Terrain table setup failed: " + exception.getMessage());
        }
    }

    public void shutdown() {
        for (Map.Entry<Integer, FloorGenerationJob> entry : new ArrayList<>(this.activeGenerationJobs.entrySet())) {
            entry.getValue().cancel("Plugin disabled before generation completed.");
        }
        this.activeGenerationJobs.clear();
    }

    private ConfigurationSection floorSection(int floorNumber) {
        return this.plugin.getConfig().getConfigurationSection("terrain.floors." + floorNumber);
    }

    private boolean hasVillageBlocks(World world, TerrainPoint point) {
        TerrainTheme theme = this.theme(point.floor());
        int matches = 0;
        for (int dx = -58; dx <= 58; dx += 4) {
            for (int dz = -58; dz <= 58; dz += 4) {
                int x = point.x() + dx;
                int z = point.z() + dz;
                Material material = world.getBlockAt(x, Math.max(world.getMinHeight(), world.getHighestBlockYAt(x, z)), z).getType();
                if (material == theme.road()
                        || material == theme.wall()
                        || material == theme.roof()
                        || material == Material.FARMLAND
                        || material == Material.WATER) {
                    matches++;
                    if (matches >= 12) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasArenaBlocks(World world, TerrainPoint point) {
        TerrainTheme theme = this.theme(point.floor());
        int matches = 0;
        for (int dx = -52; dx <= 52; dx += 4) {
            for (int dz = -52; dz <= 52; dz += 4) {
                int x = point.x() + dx;
                int z = point.z() + dz;
                Material material = world.getBlockAt(x, Math.max(world.getMinHeight(), world.getHighestBlockYAt(x, z)), z).getType();
                if (material == theme.road() || material == theme.wall() || material == theme.accent() || material == Material.STONE_BRICKS) {
                    matches++;
                    if (matches >= 16) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasPointBlocks(World world, List<TerrainPoint> points, Material expected) {
        for (TerrainPoint point : points) {
            world.getChunkAt(point.x() >> 4, point.z() >> 4).load(true);
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    int x = point.x() + dx;
                    int z = point.z() + dz;
                    for (int y = Math.max(world.getMinHeight(), point.y()); y <= Math.min(world.getMaxHeight() - 1, point.y() + 12); y++) {
                        if (world.getBlockAt(x, y, z).getType() == expected) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasFarmBlocks(World world, List<TerrainPoint> points) {
        for (TerrainPoint point : points) {
            if (!point.key().equalsIgnoreCase("farm-gate")) {
                continue;
            }
            world.getChunkAt(point.x() >> 4, point.z() >> 4).load(true);
            int matches = 0;
            for (int dx = -72; dx <= 72; dx += 2) {
                for (int dz = -48; dz <= 48; dz += 2) {
                    int x = point.x() + dx;
                    int z = point.z() + dz;
                    int y = Math.max(world.getMinHeight(), world.getHighestBlockYAt(x, z));
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material == Material.FARMLAND || material == Material.WATER) {
                        matches++;
                        if (matches >= 18) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasSetMapHouseBlocks(World world, TerrainPoint origin) {
        int matches = 0;
        for (int dx = -128; dx <= 128; dx += 4) {
            for (int dz = -104; dz <= 104; dz += 4) {
                int x = origin.x() + dx;
                int z = origin.z() + dz;
                world.getChunkAt(x >> 4, z >> 4).load(true);
                for (int y = Math.max(world.getMinHeight(), origin.y() - 8); y <= Math.min(world.getMaxHeight() - 1, origin.y() + 24); y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.DARK_OAK_PLANKS || type == Material.STRIPPED_SPRUCE_LOG || type == Material.OAK_PLANKS || type == Material.STONE_BRICKS) {
                        matches++;
                        if (matches >= 32) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasAnyHeroTreeBlock(World world, TerrainPoint origin) {
        for (int dx = -96; dx <= 96; dx += 4) {
            for (int dz = -96; dz <= 96; dz += 4) {
                int x = origin.x() + dx;
                int z = origin.z() + dz;
                for (int y = Math.max(world.getMinHeight(), origin.y()); y <= Math.min(world.getMaxHeight() - 1, origin.y() + 24); y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.OAK_LOG || type == Material.STRIPPED_OAK_LOG || type == Material.AZALEA_LEAVES) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<StructurePlacement> plannedPlacements(int floorNumber, String worldName) {
        TerrainTheme theme = this.theme(floorNumber);
        List<TerrainPoint> villages = this.getVillages(floorNumber, worldName);
        TerrainPoint arena = this.getBossArena(floorNumber, worldName);
        List<TerrainPoint> landmarks = this.getLandmarks(floorNumber, worldName);
        List<TerrainPoint> livingPoints = this.getLivingPoints(floorNumber, worldName);
        return FloorStructurePlanner.plan(floorNumber, worldName, theme, this.structureTemplateManager, villages, arena, landmarks, livingPoints);
    }

    private int countPlannedNear(List<StructurePlacement> placements, TerrainPoint origin, int radius) {
        int count = 0;
        for (StructurePlacement placement : placements) {
            if (Math.hypot(placement.originX() - origin.x(), placement.originZ() - origin.z()) <= radius) {
                count++;
            }
        }
        return count;
    }

    private int countPhysicalPlacements(World world, List<StructurePlacement> placements, TerrainPoint origin, int radius) {
        int count = 0;
        for (StructurePlacement placement : placements) {
            if (Math.hypot(placement.originX() - origin.x(), placement.originZ() - origin.z()) > radius) {
                continue;
            }
            world.getChunkAt(placement.originX() >> 4, placement.originZ() >> 4).load(true);
            int matches = 0;
            int minX = Math.max(placement.minWorldX(), placement.originX() - 24);
            int maxX = Math.min(placement.maxWorldX(), placement.originX() + 24);
            int minZ = Math.max(placement.minWorldZ(), placement.originZ() - 24);
            int maxZ = Math.min(placement.maxWorldZ(), placement.originZ() + 24);
            for (int x = minX; x <= maxX; x += 2) {
                for (int z = minZ; z <= maxZ; z += 2) {
                    for (int y = Math.max(world.getMinHeight(), placement.baseY() - 2); y <= Math.min(world.getMaxHeight() - 1, placement.baseY() + 16); y++) {
                        Material type = world.getBlockAt(x, y, z).getType();
                        if (this.isAuthoredMaterial(type)) {
                            matches++;
                            if (matches >= 4) {
                                count++;
                                x = maxX + 1;
                                z = maxZ + 1;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean hasRoadNetwork(World world, int floorNumber, String worldName, TerrainPoint hub, TerrainPoint arena) {
        List<TerrainPoint> targets = new ArrayList<>();
        targets.addAll(this.getPoints(floorNumber, worldName, "camp"));
        targets.addAll(this.getPoints(floorNumber, worldName, "waystone"));
        targets.addAll(this.getPoints(floorNumber, worldName, "shrine"));
        targets.addAll(this.getPoints(floorNumber, worldName, "road_marker"));
        if (arena != null) {
            targets.add(arena);
        }
        int checked = 0;
        int passed = 0;
        for (TerrainPoint target : targets) {
            checked++;
            if (this.roadCoverage(world, hub, target) >= 0.32D) {
                passed++;
            }
        }
        return checked > 0 && passed >= Math.max(3, checked - 1);
    }

    private double roadCoverage(World world, TerrainPoint a, TerrainPoint b) {
        double length = Math.max(1.0D, Math.hypot(b.x() - a.x(), b.z() - a.z()));
        int samples = Math.max(8, (int) Math.round(length / 12.0D));
        int hits = 0;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(a.x() + (b.x() - a.x()) * t);
            int z = (int) Math.round(a.z() + (b.z() - a.z()) * t);
            world.getChunkAt(x >> 4, z >> 4).load(true);
            if (this.hasRoadMaterialNear(world, x, z)) {
                hits++;
            }
        }
        return hits / (double) (samples + 1);
    }

    private boolean hasRoadMaterialNear(World world, int x, int z) {
        TerrainTheme theme = this.theme(1);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int sampleX = x + dx;
                int sampleZ = z + dz;
                int y = Math.max(world.getMinHeight(), world.getHighestBlockYAt(sampleX, sampleZ));
                for (int yy = Math.max(world.getMinHeight(), y - 2); yy <= Math.min(world.getMaxHeight() - 1, y + 1); yy++) {
                    Material type = world.getBlockAt(sampleX, yy, sampleZ).getType();
                    if (type == theme.road() || type == Material.STONE_BRICKS || type == Material.COBBLESTONE || type == Material.LANTERN) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasHydrologyBlocks(World world, TerrainPoint origin) {
        int water = 0;
        for (int dx = -320; dx <= 320; dx += 16) {
            for (int dz = -320; dz <= 320; dz += 16) {
                int x = origin.x() + dx;
                int z = origin.z() + dz;
                int y = world.getHighestBlockYAt(x, z);
                for (int yy = Math.max(world.getMinHeight(), y - 3); yy <= Math.min(world.getMaxHeight() - 1, y + 1); yy++) {
                    Material type = world.getBlockAt(x, yy, z).getType();
                    if (type == Material.WATER || type == Material.MUD) {
                        water++;
                        if (water >= 8) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasRegionVariety(int floorNumber, String worldName) {
        List<TerrainRegion> regions = new ArrayList<>();
        int[][] samples = {
                {0, 0}, {-680, 160}, {720, 520}, {120, 620}, {0, -680}, {960, 768}, {-460, -420}, {420, -260}
        };
        for (int[] sample : samples) {
            TerrainRegion region = TerrainLayout.region(floorNumber, worldName, sample[0], sample[1]);
            if (!regions.contains(region)) {
                regions.add(region);
            }
        }
        return regions.size() >= 5;
    }

    private boolean isAuthoredMaterial(Material type) {
        return type == Material.STONE_BRICKS
                || type == Material.MOSSY_STONE_BRICKS
                || type == Material.COBBLESTONE
                || type == Material.OAK_PLANKS
                || type == Material.OAK_LOG
                || type == Material.STRIPPED_OAK_LOG
                || type == Material.DARK_OAK_PLANKS
                || type == Material.COPPER_BLOCK
                || type == Material.LANTERN
                || type == Material.FARMLAND
                || type == Material.BRICKS
                || type == Material.CAMPFIRE;
    }

    private boolean isStructureEnabled(int floorNumber, String type) {
        List<String> enabled = this.plugin.getConfig().getStringList("terrain.floors." + floorNumber + ".enabled-structures");
        return enabled.isEmpty() || enabled.stream().map(TerrainManager::normalizeType).anyMatch(type::equals);
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String cacheKey(int floor, String worldName, String type, String key) {
        return floor + ":" + (worldName == null ? "" : worldName.toLowerCase(Locale.ROOT)) + ":" + type + ":" + key;
    }
}
