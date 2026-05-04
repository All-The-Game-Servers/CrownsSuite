package com.xkstudios.crowns.mmo.floor;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.TerrainPoint;
import com.xkstudios.crowns.api.TerrainProvider;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.mmo.MmoSkill;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;

public class MmoFloorManager {
    private final CrownsPlugin plugin;
    private final DataManager dataManager;
    private final NamespacedKey floorBossKey;
    private final Map<Integer, MmoFloor> floors = new LinkedHashMap<>();
    private final Map<String, Integer> floorByWorld = new HashMap<>();
    private final Map<Integer, Location> bossLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> unlockCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeBosses = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeBossStarters = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> activeBossPartyMembers = new ConcurrentHashMap<>();

    public MmoFloorManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.floorBossKey = new NamespacedKey(plugin, "floor_boss");
    }

    public void initialize() {
        this.ensureTables();
        this.loadFloors();
        this.loadBossLocations();
        this.ensureFirstFloor();
    }

    public List<MmoFloor> getFloors() {
        return this.floors.values().stream()
                .sorted(Comparator.comparingInt(MmoFloor::number))
                .toList();
    }

    public MmoFloor getFloor(int number) {
        return this.floors.get(number);
    }

    public MmoFloor getFloor(World world) {
        if (world == null) {
            return null;
        }
        Integer number = this.floorByWorld.get(world.getName().toLowerCase(Locale.ROOT));
        return number == null ? null : this.floors.get(number);
    }

    public int getFloorNumber(World world) {
        MmoFloor floor = this.getFloor(world);
        return floor == null ? 0 : floor.number();
    }

    public Location getBossLocation(int floorNumber) {
        return this.bossLocations.get(floorNumber);
    }

    public boolean hasUnlocked(Player player, int floorNumber) {
        if (player == null) {
            return false;
        }
        if (floorNumber <= 1) {
            return true;
        }
        this.ensureUnlocksLoaded(player.getUniqueId());
        return this.unlockCache.getOrDefault(player.getUniqueId(), Set.of()).contains(floorNumber);
    }

    public int getHighestUnlocked(Player player) {
        if (player == null) {
            return 1;
        }
        this.ensureUnlocksLoaded(player.getUniqueId());
        int highest = 1;
        for (Integer floor : this.unlockCache.getOrDefault(player.getUniqueId(), Set.of())) {
            highest = Math.max(highest, floor);
        }
        return highest;
    }

    public String formatUnlockLine(Player player, int floorNumber) {
        if (this.hasUnlocked(player, floorNumber)) {
            return "Unlocked";
        }
        MmoFloor floor = this.floors.get(floorNumber);
        int required = floor == null ? Math.max(1, floorNumber - 1) : Math.max(1, floor.requiredFloor());
        return "Locked: clear Floor " + required + " boss";
    }

    public boolean teleportToFloor(Player player, int floorNumber) {
        MmoFloor floor = this.floors.get(floorNumber);
        if (floor == null) {
            player.sendMessage(Component.text("That floor is not configured yet.", NamedTextColor.RED));
            return false;
        }
        if (!this.hasUnlocked(player, floorNumber)) {
            player.sendMessage(Component.text(this.formatUnlockLine(player, floorNumber), NamedTextColor.RED));
            return false;
        }
        World world = this.ensureWorld(floor);
        if (world == null) {
            player.sendMessage(Component.text("Floor " + floorNumber + " could not be loaded.", NamedTextColor.RED));
            return false;
        }
        this.ensureBossLocation(floor);
        Location spawn = this.spawnLocation(floor, world);
        if (!player.teleport(spawn)) {
            player.sendMessage(Component.text("Floor " + floorNumber + " is not ready for teleporting yet.", NamedTextColor.RED));
            return false;
        }
        player.sendMessage(Component.text("Entered Floor " + floorNumber + ".", NamedTextColor.AQUA));
        return true;
    }

    public boolean setSpawn(Player player, int floorNumber) {
        MmoFloor floor = this.floors.get(floorNumber);
        if (floor == null) {
            player.sendMessage(Component.text("That floor is not configured.", NamedTextColor.RED));
            return false;
        }
        this.plugin.getConfig().set("mmo.floors.list." + floorNumber + ".spawn.x", player.getLocation().getX());
        this.plugin.getConfig().set("mmo.floors.list." + floorNumber + ".spawn.y", player.getLocation().getY());
        this.plugin.getConfig().set("mmo.floors.list." + floorNumber + ".spawn.z", player.getLocation().getZ());
        this.plugin.getConfig().set("mmo.floors.list." + floorNumber + ".spawn.yaw", player.getLocation().getYaw());
        this.plugin.getConfig().set("mmo.floors.list." + floorNumber + ".spawn.pitch", player.getLocation().getPitch());
        this.plugin.saveConfig();
        player.sendMessage(Component.text("Floor " + floorNumber + " spawn saved.", NamedTextColor.GREEN));
        return true;
    }

    public boolean setBossLocation(Player player, int floorNumber) {
        MmoFloor floor = this.floors.get(floorNumber);
        if (floor == null) {
            player.sendMessage(Component.text("That floor is not configured.", NamedTextColor.RED));
            return false;
        }
        Location location = player.getLocation();
        this.bossLocations.put(floorNumber, location);
        this.saveBossLocation(floorNumber, floor.worldName(), location);
        player.sendMessage(Component.text("Floor " + floorNumber + " boss arena saved here.", NamedTextColor.GREEN));
        return true;
    }

    public boolean unlockFloor(Player target, int floorNumber, String source) {
        if (target == null || !this.floors.containsKey(floorNumber)) {
            return false;
        }
        if (floorNumber <= 1 || this.hasUnlocked(target, floorNumber)) {
            return false;
        }
        this.ensureUnlocksLoaded(target.getUniqueId());
        this.unlockCache.computeIfAbsent(target.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(floorNumber);
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_floor_unlocks (player_uuid, player_name, floor_number, source, unlocked_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getName());
            statement.setInt(3, floorNumber);
            statement.setString(4, source == null ? "unknown" : source);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save floor unlock: " + exception.getMessage());
        }
        target.sendMessage(Component.text("Floor " + floorNumber + " unlocked.", NamedTextColor.GOLD));
        CrownsAPI.publishAlert("mmo", "Floor unlocked", target.getName() + " unlocked Floor " + floorNumber + ".", target.getUniqueId(), false);
        return true;
    }

    public void describeBoss(Player player) {
        MmoFloor floor = this.getFloor(player.getWorld());
        if (floor == null) {
            player.sendMessage(Component.text("This world is not attached to a CrownsMMO floor.", NamedTextColor.RED));
            return;
        }
        Location location = this.ensureBossLocation(floor);
        if (location == null) {
            player.sendMessage(Component.text("No boss arena exists for this floor yet.", NamedTextColor.RED));
            return;
        }
        double distance = player.getWorld().equals(location.getWorld()) ? player.getLocation().distance(location) : -1.0D;
        player.sendMessage(Component.text("Floor " + floor.number() + " Boss: " + floor.bossName(), NamedTextColor.GOLD));
        player.sendMessage(Component.text("Arena: X " + location.getBlockX() + ", Z " + location.getBlockZ(), NamedTextColor.GRAY));
        if (distance >= 0.0D && distance <= floor.bossArenaRadius() + 32.0D) {
            player.sendMessage(Component.text("You are in range. Run /cmmo boss start to begin the encounter.", NamedTextColor.AQUA));
        } else {
            player.sendMessage(Component.text("Reach the arena to start the encounter.", NamedTextColor.YELLOW));
        }
    }

    public boolean startBoss(Player player) {
        MmoFloor floor = this.getFloor(player.getWorld());
        if (floor == null) {
            player.sendMessage(Component.text("This world is not attached to a CrownsMMO floor.", NamedTextColor.RED));
            return false;
        }
        if (!this.hasUnlocked(player, floor.number())) {
            player.sendMessage(Component.text("You have not unlocked this floor.", NamedTextColor.RED));
            return false;
        }
        if (this.activeBosses.containsValue(floor.number())) {
            player.sendMessage(Component.text("This Floor Boss is already active.", NamedTextColor.YELLOW));
            return false;
        }
        Location location = this.ensureBossLocation(floor);
        if (location == null || location.getWorld() == null) {
            player.sendMessage(Component.text("No boss arena exists for this floor yet.", NamedTextColor.RED));
            return false;
        }
        if (!player.getWorld().equals(location.getWorld()) || player.getLocation().distance(location) > floor.bossArenaRadius() + 32.0D) {
            player.sendMessage(Component.text("You must be at the Floor " + floor.number() + " boss arena first.", NamedTextColor.RED));
            return false;
        }
        Entity entity = location.getWorld().spawnEntity(location, floor.bossType());
        if (!(entity instanceof LivingEntity boss)) {
            entity.remove();
            player.sendMessage(Component.text("That boss type cannot be spawned as a living boss.", NamedTextColor.RED));
            return false;
        }
        boss.customName(Component.text(floor.bossName(), NamedTextColor.DARK_PURPLE));
        boss.setCustomNameVisible(true);
        boss.getPersistentDataContainer().set(this.floorBossKey, PersistentDataType.INTEGER, floor.number());
        this.applyBossAttributes(boss, floor);
        this.activeBosses.put(boss.getUniqueId(), floor.number());
        this.activeBossStarters.put(boss.getUniqueId(), player.getUniqueId());
        List<UUID> partyMembers = this.plugin.getPartyManager().members(player.getUniqueId());
        if (!partyMembers.isEmpty()) {
            this.activeBossPartyMembers.put(boss.getUniqueId(), partyMembers);
        }
        Bukkit.broadcast(Component.text("Floor " + floor.number() + " Boss awakened: " + floor.bossName(), NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    public void handleBossDeath(LivingEntity boss) {
        Integer floorNumber = boss.getPersistentDataContainer().get(this.floorBossKey, PersistentDataType.INTEGER);
        if (floorNumber == null) {
            return;
        }
        this.activeBosses.remove(boss.getUniqueId());
        UUID starterId = this.activeBossStarters.remove(boss.getUniqueId());
        List<UUID> partyMembers = this.activeBossPartyMembers.remove(boss.getUniqueId());
        MmoFloor floor = this.floors.get(floorNumber);
        if (floor == null) {
            return;
        }
        int nextFloor = floor.nextFloor();
        List<Player> credited = this.plugin.getPartyManager().resolveBossCredit(starterId, partyMembers, boss.getLocation(), floor.bossArenaRadius());
        for (Player player : credited) {
            this.recordBossClear(player, floor, "Floor " + floor.number() + " Boss");
            this.plugin.getMmoManager().addXp(player, MmoSkill.EXPLORATION, this.plugin.getConfig().getLong("mmo.skills.exploration.boss-xp", 50L), "floor-boss:" + floor.number());
            this.plugin.getMmoManager().addXp(player, MmoSkill.SWORDSMANSHIP, this.plugin.getConfig().getLong("mmo.floors.boss-clear-combat-xp", 80L), "floor-boss:" + floor.number());
            this.plugin.getQuestManager().increment(player, "boss", "floor_" + floor.number() + "_boss", 1);
            this.plugin.getItemFactory().awardBossLoot(player, floor);
            if (this.floors.containsKey(nextFloor)) {
                this.unlockFloor(player, nextFloor, "floor_" + floor.number() + "_boss");
            }
        }
        Bukkit.broadcast(Component.text("Floor " + floor.number() + " Boss defeated by " + Math.max(1, credited.size()) + " adventurer(s).", NamedTextColor.GOLD));
    }

    public void scaleFloorMob(LivingEntity entity) {
        MmoFloor floor = this.getFloor(entity.getWorld());
        if (floor == null || floor.number() <= 1 || !this.shouldScale(entity)) {
            return;
        }
        if (entity.getPersistentDataContainer().has(this.floorBossKey, PersistentDataType.INTEGER)) {
            return;
        }
        double difficulty = Math.max(1.0D, floor.difficulty());
        this.multiplyAttribute(entity, Attribute.MAX_HEALTH, difficulty);
        this.multiplyAttribute(entity, Attribute.ATTACK_DAMAGE, Math.max(1.0D, 0.75D + difficulty * 0.35D));
        this.multiplyAttribute(entity, Attribute.ARMOR, Math.max(1.0D, 0.8D + difficulty * 0.20D));
        this.multiplyAttribute(entity, Attribute.MOVEMENT_SPEED, Math.min(1.6D, 1.0D + (difficulty - 1.0D) * 0.05D));
        AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            entity.setHealth(Math.min(health.getValue(), health.getBaseValue()));
        }
        if (this.plugin.getConfig().getBoolean("mmo.floors.mob-scaling.show-nameplates", true)) {
            entity.customName(Component.text("F" + floor.number() + " " + this.prettyEntity(entity.getType()), NamedTextColor.RED));
            entity.setCustomNameVisible(false);
        }
    }

    private void ensureTables() {
        try (Statement statement = this.dataManager.getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_floor_boss_locations (
                        floor_number INTEGER PRIMARY KEY,
                        world_name TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL DEFAULT 0,
                        pitch REAL NOT NULL DEFAULT 0,
                        chosen_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_floor_unlocks (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        floor_number INTEGER NOT NULL,
                        source TEXT,
                        unlocked_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, floor_number)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_floor_boss_clears (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        floor_number INTEGER NOT NULL,
                        boss_name TEXT,
                        cleared_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, floor_number)
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Floor table setup failed: " + exception.getMessage());
        }
    }

    private void loadFloors() {
        this.floors.clear();
        this.floorByWorld.clear();
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("mmo.floors.list");
        if (section == null) {
            this.seedFallbackFloors();
            return;
        }
        for (String key : section.getKeys(false)) {
            int number = this.parseFloorNumber(key);
            if (number <= 0) {
                continue;
            }
            ConfigurationSection floorSection = section.getConfigurationSection(key);
            if (floorSection == null) {
                continue;
            }
            MmoFloor floor = this.readFloor(number, floorSection);
            this.floors.put(number, floor);
            this.floorByWorld.put(floor.worldName().toLowerCase(Locale.ROOT), floor.number());
        }
        if (!this.floors.containsKey(1)) {
            MmoFloor floorOne = this.defaultFloor(1);
            this.floors.put(1, floorOne);
            this.floorByWorld.put(floorOne.worldName().toLowerCase(Locale.ROOT), 1);
        }
    }

    private void seedFallbackFloors() {
        for (int floorNumber = 1; floorNumber <= 5; floorNumber++) {
            MmoFloor floor = this.defaultFloor(floorNumber);
            this.floors.put(floorNumber, floor);
            this.floorByWorld.put(floor.worldName().toLowerCase(Locale.ROOT), floor.number());
        }
    }

    private MmoFloor readFloor(int number, ConfigurationSection section) {
        String prefix = this.plugin.getConfig().getString("mmo.floors.generated-world-prefix", "crowns_floor_");
        String floorOneWorld = this.plugin.getConfig().getString("mmo.floors.floor-1-world", "world");
        String worldName = section.getString("world", number == 1 ? floorOneWorld : prefix + number);
        World.Environment environment = this.readEnvironment(section.getString("environment", "NORMAL"));
        int border = section.getInt("border-size", this.defaultBorderSize(number));
        int requiredFloor = section.getInt("required-floor", Math.max(1, number - 1));
        double difficulty = section.getDouble("difficulty", Math.max(1.0D, Math.pow(1.75D, number - 1)));
        EntityType bossType = this.readEntityType(section.getString("boss.type", number == 1 ? "ZOMBIE" : "WARDEN"), EntityType.ZOMBIE);
        String bossName = section.getString("boss.name", "Floor " + number + " Boss");
        double bossHealth = section.getDouble("boss.health", 160.0D * Math.max(1.0D, difficulty));
        double bossDamage = section.getDouble("boss.damage", 8.0D * Math.max(1.0D, difficulty));
        double arenaRadius = section.getDouble("boss.arena-radius", 72.0D);
        long resourceTier = section.getLong("resource-tier", number);
        return new MmoFloor(number, worldName, environment, border, requiredFloor, difficulty, bossType, bossName, bossHealth, bossDamage, arenaRadius, resourceTier);
    }

    private MmoFloor defaultFloor(int number) {
        String prefix = this.plugin.getConfig().getString("mmo.floors.generated-world-prefix", "crowns_floor_");
        String floorOneWorld = this.plugin.getConfig().getString("mmo.floors.floor-1-world", "world");
        String worldName = number == 1 ? floorOneWorld : prefix + number;
        double difficulty = Math.max(1.0D, Math.pow(1.75D, number - 1));
        EntityType bossType = number == 1 ? EntityType.ZOMBIE : EntityType.WARDEN;
        String bossName = number == 1 ? "The First Gatekeeper" : "Floor " + number + " Gatekeeper";
        return new MmoFloor(number, worldName, World.Environment.NORMAL, this.defaultBorderSize(number), Math.max(1, number - 1), difficulty, bossType, bossName, 160.0D * difficulty, 8.0D * difficulty, 72.0D, number);
    }

    private int defaultBorderSize(int floorNumber) {
        if (floorNumber <= 1) {
            return this.plugin.getConfig().getInt("mmo.floors.default-border-size", 16000);
        }
        return this.plugin.getConfig().getInt("mmo.floors.generated-border-size", 8000);
    }

    private void ensureFirstFloor() {
        MmoFloor floor = this.floors.get(1);
        if (floor == null) {
            return;
        }
        World world = this.ensureWorld(floor);
        if (world != null) {
            this.applyBorder(world, floor.borderSize());
            this.applyFirstHavenSpawn(floor, world);
            this.ensureBossLocation(floor);
        } else {
            this.plugin.getLogger().warning("[CrownsMMO] Floor 1 world '" + floor.worldName() + "' is not loaded yet.");
        }
    }

    private World ensureWorld(MmoFloor floor) {
        World world = Bukkit.getWorld(floor.worldName());
        if (world == null) {
            WorldCreator creator = new WorldCreator(floor.worldName()).environment(floor.environment());
            TerrainProvider terrain = CrownsAPI.getTerrain();
            if (terrain != null) {
                ChunkGenerator generator = terrain.getGeneratorForFloor(floor.number(), floor.worldName(), floor.environment(), floor.resourceTier());
                if (generator != null) {
                    creator.generator(generator);
                }
            }
            world = creator.createWorld();
        }
        if (world != null) {
            this.applyBorder(world, floor.borderSize());
            if (floor.number() == 1) {
                this.applyFirstHavenSpawn(floor, world);
            }
        }
        return world;
    }

    private void applyBorder(World world, int borderSize) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0D, 0.0D);
        border.setSize(Math.max(1, borderSize));
    }

    private Location spawnLocation(MmoFloor floor, World world) {
        String path = "mmo.floors.list." + floor.number() + ".spawn";
        if (this.plugin.getConfig().isConfigurationSection(path)) {
            return new Location(
                    world,
                    this.plugin.getConfig().getDouble(path + ".x", world.getSpawnLocation().getX()),
                    this.plugin.getConfig().getDouble(path + ".y", world.getSpawnLocation().getY()),
                    this.plugin.getConfig().getDouble(path + ".z", world.getSpawnLocation().getZ()),
                    (float) this.plugin.getConfig().getDouble(path + ".yaw", 0.0D),
                    (float) this.plugin.getConfig().getDouble(path + ".pitch", 0.0D)
            );
        }
        if (floor.number() == 1) {
            Location firstHaven = this.firstHavenSpawn(floor, world);
            if (firstHaven != null) {
                return firstHaven;
            }
        }
        return world.getSpawnLocation();
    }

    public boolean startPlayer(Player player) {
        MmoFloor floor = this.floors.get(Math.max(1, this.plugin.getConfig().getInt("mmo.onboarding.start-floor", 1)));
        if (floor == null) {
            player.sendMessage(Component.text("CrownsMMO has no starting floor configured.", NamedTextColor.RED));
            return false;
        }
        if (!this.teleportToFloor(player, floor.number())) {
            return false;
        }
        this.plugin.getMmoManager().markWorldProgress(player, "onboarding:first_haven_start", "Started at First Haven");
        player.sendMessage(Component.text("Welcome to First Haven. Open /cmmo quests and begin the First Haven Path.", NamedTextColor.GOLD));
        return true;
    }

    private void applyFirstHavenSpawn(MmoFloor floor, World world) {
        Location spawn = this.firstHavenSpawn(floor, world);
        if (spawn != null) {
            world.setSpawnLocation(spawn);
        }
    }

    private Location firstHavenSpawn(MmoFloor floor, World world) {
        TerrainProvider terrain = CrownsAPI.getTerrain();
        if (terrain == null) {
            return null;
        }
        TerrainPoint point = terrain.getVillages(floor.number(), floor.worldName()).stream()
                .filter(candidate -> candidate.key().equalsIgnoreCase("first-haven") || candidate.displayName().equalsIgnoreCase("First Haven"))
                .findFirst()
                .orElse(null);
        if (point == null) {
            return null;
        }
        world.getChunkAt(point.x() >> 4, point.z() >> 4).load(true);
        int y = Math.max(world.getMinHeight() + 2, world.getHighestBlockYAt(point.x(), point.z()) + 1);
        return new Location(world, point.x() + 0.5D, y, point.z() + 0.5D, 0.0F, 0.0F);
    }

    private Location ensureBossLocation(MmoFloor floor) {
        Location existing = this.bossLocations.get(floor.number());
        if (existing != null && existing.getWorld() != null) {
            return existing;
        }
        World world = this.ensureWorld(floor);
        if (world == null) {
            return null;
        }
        Location saved = this.loadBossLocation(floor, world);
        if (saved != null) {
            this.bossLocations.put(floor.number(), saved);
            return saved;
        }
        TerrainProvider terrain = CrownsAPI.getTerrain();
        if (terrain != null) {
            TerrainPoint arena = terrain.getBossArena(floor.number(), floor.worldName());
            Location terrainLocation = arena == null ? null : arena.toLocation();
            if (terrainLocation != null) {
                this.bossLocations.put(floor.number(), terrainLocation);
                this.saveBossLocation(floor.number(), floor.worldName(), terrainLocation);
                return terrainLocation;
            }
        }
        Location chosen = this.chooseBossLocation(floor, world);
        this.bossLocations.put(floor.number(), chosen);
        this.saveBossLocation(floor.number(), floor.worldName(), chosen);
        return chosen;
    }

    private Location chooseBossLocation(MmoFloor floor, World world) {
        int half = Math.max(512, floor.borderSize() / 2 - 256);
        Random seeded = new Random(world.getSeed() ^ (floor.number() * 341873128712L));
        for (int i = 0; i < 16; i++) {
            int x = seeded.nextInt(half * 2) - half;
            int z = seeded.nextInt(half * 2) - half;
            int y = Math.max(world.getMinHeight() + 4, world.getHighestBlockYAt(x, z) + 1);
            if (y < world.getMaxHeight() - 4) {
                return new Location(world, x + 0.5D, y, z + 0.5D);
            }
        }
        Location spawn = world.getSpawnLocation();
        return spawn.clone().add(128.0D, 0.0D, 128.0D);
    }

    private void loadBossLocations() {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT floor_number, world_name, x, y, z, yaw, pitch FROM mmo_floor_boss_locations")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    World world = Bukkit.getWorld(result.getString("world_name"));
                    if (world == null) {
                        continue;
                    }
                    this.bossLocations.put(result.getInt("floor_number"), new Location(
                            world,
                            result.getDouble("x"),
                            result.getDouble("y"),
                            result.getDouble("z"),
                            (float) result.getDouble("yaw"),
                            (float) result.getDouble("pitch")
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load floor boss locations: " + exception.getMessage());
        }
    }

    private Location loadBossLocation(MmoFloor floor, World world) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT x, y, z, yaw, pitch FROM mmo_floor_boss_locations
                WHERE floor_number = ? AND world_name = ?
                """)) {
            statement.setInt(1, floor.number());
            statement.setString(2, floor.worldName());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new Location(
                            world,
                            result.getDouble("x"),
                            result.getDouble("y"),
                            result.getDouble("z"),
                            (float) result.getDouble("yaw"),
                            (float) result.getDouble("pitch")
                    );
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load saved floor boss location: " + exception.getMessage());
        }
        return null;
    }

    private void saveBossLocation(int floorNumber, String worldName, Location location) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_floor_boss_locations (floor_number, world_name, x, y, z, yaw, pitch, chosen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, floorNumber);
            statement.setString(2, worldName);
            statement.setDouble(3, location.getX());
            statement.setDouble(4, location.getY());
            statement.setDouble(5, location.getZ());
            statement.setDouble(6, location.getYaw());
            statement.setDouble(7, location.getPitch());
            statement.setLong(8, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save floor boss location: " + exception.getMessage());
        }
    }

    private void ensureUnlocksLoaded(UUID playerId) {
        this.unlockCache.computeIfAbsent(playerId, ignored -> {
            Set<Integer> floors = ConcurrentHashMap.newKeySet();
            try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                    "SELECT floor_number FROM mmo_floor_unlocks WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        floors.add(result.getInt("floor_number"));
                    }
                }
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[CrownsMMO] Could not load floor unlocks: " + exception.getMessage());
            }
            return floors;
        });
    }

    private void recordBossClear(Player player, MmoFloor floor, String bossName) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_floor_boss_clears (player_uuid, player_name, floor_number, boss_name, cleared_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setInt(3, floor.number());
            statement.setString(4, bossName);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save boss clear: " + exception.getMessage());
        }
    }

    private void applyBossAttributes(LivingEntity boss, MmoFloor floor) {
        this.setAttribute(boss, Attribute.MAX_HEALTH, floor.bossHealth());
        this.setAttribute(boss, Attribute.ATTACK_DAMAGE, floor.bossDamage());
        this.multiplyAttribute(boss, Attribute.ARMOR, Math.max(1.0D, floor.difficulty()));
        AttributeInstance health = boss.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            boss.setHealth(Math.min(health.getValue(), floor.bossHealth()));
        }
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(Math.max(0.01D, value));
        }
    }

    private void multiplyAttribute(LivingEntity entity, Attribute attribute, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(Math.max(0.01D, instance.getBaseValue() * multiplier));
        }
    }

    private boolean shouldScale(LivingEntity entity) {
        if (entity instanceof Player || entity instanceof Animals || entity instanceof Ambient || entity instanceof NPC) {
            return false;
        }
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast || entity instanceof Shulker;
    }

    private EntityType readEntityType(String key, EntityType fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return EntityType.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private World.Environment readEnvironment(String key) {
        if (key == null || key.isBlank()) {
            return World.Environment.NORMAL;
        }
        try {
            return World.Environment.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return World.Environment.NORMAL;
        }
    }

    private int parseFloorNumber(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException exception) {
            String digits = key.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return -1;
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
    }

    private String prettyEntity(EntityType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
