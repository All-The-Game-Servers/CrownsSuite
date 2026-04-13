package com.xkstudios.crowns.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.java.JavaPlugin;

public class DataManager {
    private final JavaPlugin plugin;
    private Connection conn;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private long startingBalance = 500L;

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            this.migrateLegacyDatabase();
            File db = new File(this.plugin.getDataFolder(), "crowns.db");
            this.plugin.getDataFolder().mkdirs();
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            this.conn.setAutoCommit(true);
            this.createTables();
            this.runMigrations();
        } catch (SQLException exception) {
            throw new RuntimeException("DB init failed", exception);
        }
    }

    public void setStartingBalance(long startingBalance) {
        this.startingBalance = Math.max(0L, startingBalance);
    }

    public PlayerData getOrCreate(UUID uuid, String name) {
        PlayerData cached = this.cache.get(uuid);
        if (cached != null) {
            if (name != null && !name.isBlank()) {
                cached.setName(name);
            }
            return cached;
        }

        PlayerData loaded = this.load(uuid);
        if (loaded != null) {
            if (name != null && !name.isBlank()) {
                loaded.setName(name);
            }
            this.cache.put(uuid, loaded);
            return loaded;
        }

        PlayerData created = new PlayerData(uuid, name, this.startingBalance, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L);
        this.cache.put(uuid, created);
        this.savePlayer(created);
        return created;
    }

    public PlayerData getExistingOrCreate(UUID uuid, String fallbackName) {
        PlayerData cached = this.cache.get(uuid);
        return cached != null ? cached : this.getOrCreate(uuid, fallbackName);
    }

    public UUID findUuidByName(String name) {
        String sql = "SELECT uuid FROM players WHERE LOWER(name) = LOWER(?) ORDER BY last_join_at DESC LIMIT 1";
        try (PreparedStatement statement = this.conn.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return UUID.fromString(resultSet.getString("uuid"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Find player by name failed: " + exception.getMessage());
        }
        return null;
    }

    public void savePlayer(PlayerData data) {
        String sql = """
                INSERT OR REPLACE INTO players (
                    uuid, name, balance, total_earned, login_streak, last_login_day, last_salary_time,
                    first_join_at, last_join_at, last_quit_at, total_playtime_seconds
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = this.conn.prepareStatement(sql)) {
            statement.setString(1, data.getUuid().toString());
            statement.setString(2, data.getName());
            statement.setLong(3, data.getBalance());
            statement.setLong(4, data.getTotalEarned());
            statement.setInt(5, data.getLoginStreak());
            statement.setLong(6, data.getLastLoginDay());
            statement.setLong(7, data.getLastSalaryTime());
            statement.setLong(8, data.getFirstJoinAt());
            statement.setLong(9, data.getLastJoinAt());
            statement.setLong(10, data.getLastQuitAt());
            statement.setLong(11, data.getTotalPlaytimeSeconds());
            statement.executeUpdate();
            data.setDirty(false);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Save player failed: " + exception.getMessage());
        }
    }

    public void flushAll() {
        for (PlayerData data : this.cache.values()) {
            if (data.isDirty()) {
                this.savePlayer(data);
            }
        }
    }

    public void evict(UUID uuid) {
        PlayerData data = this.cache.remove(uuid);
        if (data != null) {
            this.savePlayer(data);
        }
    }

    public List<PlayerData> getTopBalances(int limit) {
        List<PlayerData> top = new ArrayList<>();
        try (PreparedStatement statement = this.conn.prepareStatement("SELECT * FROM players ORDER BY balance DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    top.add(this.readPlayer(resultSet));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Top query failed: " + exception.getMessage());
        }
        return top;
    }

    public Connection getConnection() {
        return this.conn;
    }

    public void close() {
        this.flushAll();
        try {
            if (this.conn != null) {
                this.conn.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private void migrateLegacyDatabase() {
        File newDb = new File(this.plugin.getDataFolder(), "crowns.db");
        if (newDb.exists()) {
            return;
        }
        File legacyDb = new File(this.plugin.getDataFolder().getParentFile(), "CrownsEconomy/crowns.db");
        if (!legacyDb.exists()) {
            return;
        }
        this.plugin.getDataFolder().mkdirs();
        try {
            Files.copy(legacyDb.toPath(), newDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.plugin.getLogger().info("Migrated legacy CrownsEconomy database into CrownsAPI.");
        } catch (IOException exception) {
            this.plugin.getLogger().warning("Legacy database migration failed: " + exception.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = this.conn.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        balance INTEGER DEFAULT 0,
                        total_earned INTEGER DEFAULT 0,
                        login_streak INTEGER DEFAULT 0,
                        last_login_day INTEGER DEFAULT 0,
                        last_salary_time INTEGER DEFAULT 0,
                        first_join_at INTEGER DEFAULT 0,
                        last_join_at INTEGER DEFAULT 0,
                        last_quit_at INTEGER DEFAULT 0,
                        total_playtime_seconds INTEGER DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bounties (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        poster_uuid TEXT NOT NULL,
                        target_uuid TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        posted_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS auction_listings (
                        id TEXT PRIMARY KEY,
                        seller_uuid TEXT NOT NULL,
                        item_data TEXT NOT NULL,
                        price INTEGER NOT NULL,
                        listed_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        sold INTEGER DEFAULT 0,
                        buyer_uuid TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS chest_shops (
                        id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        item_data TEXT NOT NULL,
                        price INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS jobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        description TEXT,
                        type TEXT,
                        target TEXT,
                        amount INTEGER,
                        reward INTEGER,
                        expires_at INTEGER,
                        claimed_by TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS playtime_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        session_start INTEGER NOT NULL,
                        session_end INTEGER NOT NULL,
                        duration_seconds INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS playtime_daily (
                        player_uuid TEXT NOT NULL,
                        play_date TEXT NOT NULL,
                        seconds_played INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, play_date)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_ledger (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT,
                        player_name TEXT,
                        category TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        detail TEXT,
                        recorded_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_inbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT,
                        created_at INTEGER NOT NULL,
                        read_at INTEGER DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moderation_vanish_state (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT,
                        stored_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS server_event_state (
                        event_key TEXT PRIMARY KEY,
                        display_name TEXT,
                        state TEXT NOT NULL,
                        previous_state TEXT NOT NULL,
                        scheduled_start_at INTEGER DEFAULT 0,
                        live_at INTEGER DEFAULT 0,
                        end_at INTEGER DEFAULT 0,
                        paused_remaining_ms INTEGER DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_milestones (
                        event_key TEXT NOT NULL,
                        milestone_key TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        target_amount INTEGER NOT NULL,
                        progress_amount INTEGER NOT NULL DEFAULT 0,
                        completed_at INTEGER DEFAULT 0,
                        PRIMARY KEY (event_key, milestone_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_player_progress (
                        event_key TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        relics INTEGER NOT NULL DEFAULT 0,
                        nether_entries INTEGER NOT NULL DEFAULT 0,
                        blaze_kills INTEGER NOT NULL DEFAULT 0,
                        ancient_debris INTEGER NOT NULL DEFAULT 0,
                        cache_loot INTEGER NOT NULL DEFAULT 0,
                        first_entry_at INTEGER DEFAULT 0,
                        first_relic_at INTEGER DEFAULT 0,
                        first_blaze_kill_at INTEGER DEFAULT 0,
                        first_ancient_debris_at INTEGER DEFAULT 0,
                        first_cache_loot_at INTEGER DEFAULT 0,
                        PRIMARY KEY (event_key, player_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_relic_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_key TEXT NOT NULL,
                        player_uuid TEXT,
                        player_name TEXT,
                        source_type TEXT NOT NULL,
                        amount INTEGER NOT NULL DEFAULT 0,
                        detail TEXT,
                        recorded_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_reward_claims (
                        event_key TEXT NOT NULL,
                        reward_key TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        claimed_at INTEGER NOT NULL,
                        PRIMARY KEY (event_key, reward_key, player_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_cache_claims (
                        event_key TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        PRIMARY KEY (event_key, player_uuid, world_name, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_stalls (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid TEXT NOT NULL,
                        owner_name TEXT,
                        category TEXT,
                        rented_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        grace_ends_at INTEGER NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        reminder_sent INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_stall_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stall_id INTEGER NOT NULL,
                        item_data TEXT NOT NULL,
                        price INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_stall_overflow (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stall_id INTEGER NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        owner_name TEXT,
                        item_data TEXT NOT NULL,
                        price INTEGER NOT NULL,
                        stored_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moderation_actions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        target_uuid TEXT,
                        target_name TEXT,
                        action_type TEXT NOT NULL,
                        reason TEXT,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER DEFAULT 0,
                        resolved_at INTEGER DEFAULT 0,
                        active INTEGER NOT NULL DEFAULT 0,
                        source TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moderation_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        reporter_uuid TEXT NOT NULL,
                        reporter_name TEXT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT,
                        reason TEXT NOT NULL,
                        status TEXT NOT NULL,
                        claimed_by_uuid TEXT,
                        claimed_by_name TEXT,
                        created_at INTEGER NOT NULL,
                        resolved_at INTEGER DEFAULT 0,
                        resolution_note TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moderation_mutes (
                        target_uuid TEXT PRIMARY KEY,
                        target_name TEXT,
                        reason TEXT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moderation_freezes (
                        target_uuid TEXT PRIMARY KEY,
                        target_name TEXT,
                        reason TEXT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS staff_roles (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT,
                        role_key TEXT NOT NULL,
                        assigned_by_uuid TEXT,
                        assigned_by_name TEXT,
                        assigned_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_storage (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT,
                        inventory_data TEXT,
                        armor_data TEXT,
                        offhand_data TEXT,
                        ender_data TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS inventory_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT,
                        storage_type TEXT NOT NULL,
                        inventory_data TEXT,
                        armor_data TEXT,
                        offhand_data TEXT,
                        ender_data TEXT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        created_at INTEGER NOT NULL,
                        reason TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS puppeteer_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        controller_uuid TEXT NOT NULL,
                        controller_name TEXT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER DEFAULT 0,
                        source TEXT
                    )
                    """);
        }
    }

    private void runMigrations() {
        this.tryAlter("ALTER TABLE players ADD COLUMN first_join_at INTEGER DEFAULT 0");
        this.tryAlter("ALTER TABLE players ADD COLUMN last_join_at INTEGER DEFAULT 0");
        this.tryAlter("ALTER TABLE players ADD COLUMN last_quit_at INTEGER DEFAULT 0");
        this.tryAlter("ALTER TABLE players ADD COLUMN total_playtime_seconds INTEGER DEFAULT 0");
        this.tryAlter("ALTER TABLE auction_listings ADD COLUMN high_bidder TEXT");
        this.tryAlter("ALTER TABLE auction_listings ADD COLUMN high_bid INTEGER DEFAULT 0");
    }

    private void tryAlter(String sql) {
        try (Statement statement = this.conn.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException ignored) {
        }
    }

    private PlayerData load(UUID uuid) {
        try (PreparedStatement statement = this.conn.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return this.readPlayer(resultSet);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Load player failed: " + exception.getMessage());
        }
        return null;
    }

    private PlayerData readPlayer(ResultSet resultSet) throws SQLException {
        return new PlayerData(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("name"),
                resultSet.getLong("balance"),
                resultSet.getLong("total_earned"),
                resultSet.getInt("login_streak"),
                resultSet.getLong("last_login_day"),
                resultSet.getLong("last_salary_time"),
                resultSet.getLong("first_join_at"),
                resultSet.getLong("last_join_at"),
                resultSet.getLong("last_quit_at"),
                resultSet.getLong("total_playtime_seconds")
        );
    }
}
