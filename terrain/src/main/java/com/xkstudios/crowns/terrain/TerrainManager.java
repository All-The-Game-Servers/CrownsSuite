package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.TerrainPoint;
import com.xkstudios.crowns.api.TerrainProvider;
import com.xkstudios.crowns.data.DataManager;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.WorldCreator;

public class TerrainManager implements TerrainProvider {
    private final CrownsTerrainPlugin plugin;
    private final DataManager dataManager;
    private final StructureTemplateManager structureTemplateManager;
    private final Map<String, TerrainPoint> pointCache = new ConcurrentHashMap<>();

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
        return this.getOrCreatePoint(TerrainLayout.defaultArena(floorNumber, worldName, this.floorSection(floorNumber)));
    }

    @Override
    public List<TerrainPoint> getVillages(int floorNumber, String worldName) {
        List<TerrainPoint> points = new ArrayList<>();
        for (TerrainPoint point : TerrainLayout.defaultVillages(floorNumber, worldName, this.floorSection(floorNumber))) {
            points.add(this.getOrCreatePoint(point));
        }
        return points;
    }

    @Override
    public List<TerrainPoint> getLandmarks(int floorNumber, String worldName) {
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
        if (floorNumber == 1) {
            return this.plugin.getConfig().getString("terrain.floor-1-world", "crowns_floor_1");
        }
        return this.plugin.getConfig().getString("terrain.generated-world-prefix", "crowns_floor_") + floorNumber;
    }

    public World createFloorWorld(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generator(this.getGeneratorForFloor(floorNumber, worldName, World.Environment.NORMAL, floorNumber));
        World world = creator.createWorld();
        if (world != null) {
            world.getWorldBorder().setCenter(0.0D, 0.0D);
            world.getWorldBorder().setSize(Math.max(1, this.getWorldSize(floorNumber)));
            this.getAllPoints(floorNumber, worldName);
        }
        return world;
    }

    public List<String> verifyFloor(int floorNumber) {
        String worldName = this.getWorldName(floorNumber);
        List<String> lines = new ArrayList<>();
        World world = Bukkit.getWorld(worldName);
        lines.add("World: " + worldName + (world == null ? " (not loaded)" : " (loaded)"));
        lines.add("Profile: " + this.getTerrainProfile(floorNumber) + " | Size: " + this.getWorldSize(floorNumber));
        lines.add("Expected world: " + worldName + (floorNumber == 1 && this.isFreshWorldRequired(floorNumber) ? " | fresh world required" : ""));
        lines.add("Bundled templates loaded: " + this.structureTemplateManager.count());
        List<TerrainPoint> villages = this.getVillages(floorNumber, worldName);
        TerrainPoint firstHaven = villages.stream()
                .filter(point -> point.key().equalsIgnoreCase("first-haven") || point.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(villages.isEmpty() ? null : villages.get(0));
        lines.add("Villages: " + villages.size() + (firstHaven == null ? "" : " | First spawn: " + firstHaven.coordinateSummary()));
        lines.add("Persisted points: " + this.countPersistedPoints(floorNumber, worldName));
        if (world == null) {
            lines.add("FAIL: world is not loaded. Run /cterrain admin create " + floorNumber + " first.");
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
        this.structureTemplateManager.load();
    }

    public TerrainTheme theme(int floorNumber) {
        ConfigurationSection section = this.floorSection(floorNumber);
        String configuredName = section == null ? null : section.getString("theme");
        return TerrainTheme.forFloor(floorNumber, configuredName);
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
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsTerrain] Terrain table setup failed: " + exception.getMessage());
        }
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
