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
                FloorStructurePlanner.plan(floorNumber, theme, this.structureTemplateManager, villages, arena, landmarks, livingPoints)
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
            lines.add(this.hasPointBlocks(world, List.of(firstHaven), Material.STONE_BRICKS)
                    ? "PASS: First Haven anchors/district foundations are present."
                    : "FAIL: First Haven anchor/district foundations were not found.");
            TerrainPoint gatehouseCheck = arena == null ? null : new TerrainPoint(floorNumber, worldName, "arena", "gatehouse-check", "Gatehouse Check", arena.x(), arena.y(), arena.z() - 58);
            lines.add(gatehouseCheck != null && this.hasPointBlocks(world, List.of(gatehouseCheck), Material.STONE_BRICKS)
                    ? "PASS: gatehouse approach blocks are present."
                    : "FAIL: gatehouse approach blocks were not found.");
            lines.add(this.hasAnyHeroTreeBlock(world, firstHaven)
                    ? "PASS: hero-tree / vertical landmark blocks are present near First Haven."
                    : "FAIL: hero-tree / vertical landmark blocks were not found near First Haven.");
            lines.add("Debug: missing structures usually mean templates are disabled, points are stale from an older world, chunks were generated before 1.5.0, or the server is still using old crowns_floor_1.");
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
