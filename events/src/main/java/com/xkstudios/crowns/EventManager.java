package com.xkstudios.crowns.event;

import com.xkstudios.crowns.CrownsPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.scheduler.BukkitTask;

public class EventManager {
    public static final String NETHER_EVENT_KEY = "nether-opening";
    public static final String END_EVENT_KEY = "end-opening";
    public static final String EVENT_KEY = NETHER_EVENT_KEY;
    private static final String SOURCE_ENTRY = "entry";
    private static final String SOURCE_CACHE = "cache";
    private static final String SOURCE_RESOURCE = "resource";
    private static final String SOURCE_MOB = "mob";
    private static final String SOURCE_ELITE = "elite";
    private static final String SOURCE_CRAFT = "craft";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static final DateTimeFormatter COMMAND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CrownsPlugin plugin;
    private final EventItemFactory itemFactory;
    private final Set<Integer> announcedCheckpoints = ConcurrentHashMap.newKeySet();
    private final Map<String, LiveMoment> liveMoments = new ConcurrentHashMap<>();
    private BossBar bossBar;
    private BukkitTask heartbeatTask;
    private EventState state = EventState.SCHEDULED;
    private EventState resumeState = EventState.SCHEDULED;
    private long scheduledStartAt;
    private long liveAt;
    private long endAt;
    private long pausedRemainingMs;

    public EventManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.itemFactory = new EventItemFactory(plugin);
    }

    public void load() {
        if (!this.isEnabled()) {
            return;
        }
        this.ensureStateRow();
        this.loadState();
        this.syncMilestones();
        this.seedStateFromConfigIfNeeded();
        this.syncDimensionLock();
        this.heartbeatTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::heartbeat, 20L, 20L);
    }

    public void shutdown() {
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel();
            this.heartbeatTask = null;
        }
        if (this.bossBar != null) {
            this.bossBar.removeAll();
            this.bossBar = null;
        }
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("events.enabled", true)
                && this.plugin.getConfig().getBoolean(this.eventRoot() + ".enabled", true);
    }

    public String getActiveEventKey() {
        String configured = this.plugin.getConfig().getString("events.active-event", NETHER_EVENT_KEY);
        if (configured == null || configured.isBlank()) {
            configured = NETHER_EVENT_KEY;
        }
        if (configured.equalsIgnoreCase(NETHER_EVENT_KEY) && this.plugin.getConfig().getBoolean("events." + NETHER_EVENT_KEY + ".enabled", true)) {
            return NETHER_EVENT_KEY;
        }
        if (configured.equalsIgnoreCase(END_EVENT_KEY) && this.plugin.getConfig().getBoolean("events." + END_EVENT_KEY + ".enabled", true)) {
            return END_EVENT_KEY;
        }
        if (this.plugin.getConfig().getBoolean("events." + NETHER_EVENT_KEY + ".enabled", true)) {
            return NETHER_EVENT_KEY;
        }
        return END_EVENT_KEY;
    }

    public boolean isEndEvent() {
        return END_EVENT_KEY.equalsIgnoreCase(this.getActiveEventKey());
    }

    public boolean isEndEvent(String eventKey) {
        return END_EVENT_KEY.equalsIgnoreCase(eventKey);
    }

    public String getMenuLabel() {
        return this.getMenuLabel(this.getActiveEventKey());
    }

    public String getMenuLabel(String eventKey) {
        return this.isEndEvent(eventKey) ? "Endfall Week" : "Nether Week";
    }

    public String getGuideTitle() {
        return this.getGuideTitle(this.getActiveEventKey());
    }

    public String getGuideTitle(String eventKey) {
        return this.isEndEvent(eventKey) ? "Endfall Guide" : "Nether Week Guide";
    }

    public String getDimensionName() {
        return this.isEndEvent() ? "The End" : "The Nether";
    }

    public String getDimensionName(String eventKey) {
        return this.isEndEvent(eventKey) ? "The End" : "The Nether";
    }

    public boolean isActiveEnvironment(World.Environment environment) {
        return environment == this.eventEnvironment();
    }

    private String eventRoot() {
        return "events." + this.getActiveEventKey();
    }

    private World.Environment eventEnvironment() {
        return this.isEndEvent() ? World.Environment.THE_END : World.Environment.NETHER;
    }

    private String dimensionLockKey() {
        return this.isEndEvent() ? "dimensions.end-locked" : "dimensions.nether-locked";
    }

    public String getTitle() {
        return this.getTitle(this.getActiveEventKey());
    }

    public String getTitle(String eventKey) {
        return this.plugin.getConfig().getString("events." + eventKey + ".title", this.isEndEvent(eventKey) ? "Endfall Opening Week" : "Nether Opening Week");
    }

    public EventState getState() {
        return this.state;
    }

    public boolean isLive() {
        return this.state == EventState.LIVE;
    }

    public boolean isPaused() {
        return this.state == EventState.PAUSED;
    }

    public long getScheduledStartAt() {
        return this.scheduledStartAt;
    }

    public long getEndAt() {
        return this.endAt;
    }

    public long getCountdownRemainingMs() {
        return Math.max(0L, this.scheduledStartAt - System.currentTimeMillis());
    }

    public long getSeasonRemainingMs() {
        if (this.state == EventState.PAUSED && this.resumeState == EventState.LIVE) {
            return Math.max(0L, this.pausedRemainingMs);
        }
        return Math.max(0L, this.endAt - System.currentTimeMillis());
    }

    public String getStatusLabel() {
        return switch (this.state) {
            case SCHEDULED -> "Scheduled";
            case COUNTDOWN -> "Countdown";
            case LIVE -> "Live";
            case PAUSED -> "Paused";
            case ENDED -> "Ended";
        };
    }

    public String getStatusDescription() {
        return switch (this.state) {
            case SCHEDULED, COUNTDOWN -> this.scheduledStartAt > 0L
                    ? "Opens " + this.formatTimestamp(this.scheduledStartAt) + " (" + this.formatDuration(this.getCountdownRemainingMs()) + ")"
                    : "No opening time scheduled yet.";
            case LIVE -> "Live now. " + this.formatDuration(this.getSeasonRemainingMs()) + " left in Opening Week.";
            case PAUSED -> this.resumeState == EventState.LIVE
                    ? "Opening Week paused with " + this.formatDuration(this.pausedRemainingMs) + " remaining."
                    : "Countdown paused with " + this.formatDuration(this.pausedRemainingMs) + " remaining.";
            case ENDED -> "Opening Week has concluded.";
        };
    }

    public String getProgressSummary(UUID playerId, String playerName) {
        EventProgress progress = this.getProgress(playerId, playerName);
        return progress.relics() + " relics, "
                + progress.cacheLoot() + " " + (this.isEndEvent() ? "survey caches" : "caches")
                + ", " + progress.blazeKills() + " " + (this.isEndEvent() ? "elite kills" : "blaze kills");
    }

    public List<String> getLiveMomentSummaries() {
        this.pruneLiveMoments();
        return this.liveMoments.values().stream()
                .sorted(Comparator.comparingLong(LiveMoment::expiresAt))
                .map(moment -> moment.label() + " - " + moment.detail())
                .toList();
    }

    public boolean triggerLiveMoment(String key, String label, String detail, long durationMs, String startedBy) {
        if (key == null || key.isBlank() || label == null || label.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(60000L, durationMs);
        LiveMoment moment = new LiveMoment(key.toLowerCase(Locale.ROOT), label, detail == null ? "" : detail, now, expiresAt, startedBy == null ? "Staff" : startedBy);
        this.liveMoments.put(moment.key(), moment);
        this.broadcast(Component.text("Live Event: " + moment.label(), NamedTextColor.LIGHT_PURPLE));
        if (!moment.detail().isBlank()) {
            this.broadcast(Component.text(moment.detail(), NamedTextColor.GRAY));
        }
        this.logEvent(null, moment.startedBy(), "live_moment_start", 0L, moment.key() + ":" + moment.label());
        return true;
    }

    public boolean stopLiveMoment(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        LiveMoment removed = this.liveMoments.remove(key.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        this.broadcast(Component.text("Live Event Ended: " + removed.label(), NamedTextColor.YELLOW));
        this.logEvent(null, removed.startedBy(), "live_moment_end", 0L, removed.key() + ":" + removed.label());
        return true;
    }

    public String getDimensionLockMessage(World.Environment environment) {
        if (environment != this.eventEnvironment() || !this.plugin.getConfig().getBoolean(this.dimensionLockKey(), true)) {
            return null;
        }
        if (!this.isEnabled()) {
            return this.getDimensionName() + " is not yet accessible.";
        }
        return switch (this.state) {
            case COUNTDOWN, SCHEDULED -> this.scheduledStartAt > 0L
                    ? this.getDimensionName() + " opens in " + this.formatDuration(this.getCountdownRemainingMs()) + "."
                    : this.getDimensionName() + " is not yet accessible.";
            case PAUSED -> this.getTitle() + " is currently paused by staff.";
            case ENDED -> this.getTitle() + " has ended, but the dimension is still locked.";
            default -> this.getDimensionName() + " is not yet accessible.";
        };
    }

    public String formatTimestamp(long millis) {
        if (millis <= 0L) {
            return "Not set";
        }
        return DISPLAY_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(Math.max(0L, millis));
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        long seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).toSeconds();
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    public void handleJoin(Player player) {
        if (!this.isEnabled()) {
            return;
        }
        this.itemFactory.migrateInventory(player);
        if (this.state == EventState.COUNTDOWN || this.state == EventState.SCHEDULED) {
            if (this.scheduledStartAt > 0L) {
                player.sendMessage(Component.text(this.getTitle() + " starts " + this.formatTimestamp(this.scheduledStartAt) + ".", NamedTextColor.GOLD));
                player.sendMessage(Component.text("Time remaining: " + this.formatDuration(this.getCountdownRemainingMs()), NamedTextColor.YELLOW));
            }
        } else if (this.state == EventState.LIVE) {
            player.sendMessage(Component.text(this.getTitle() + " is live.", NamedTextColor.GOLD));
            player.sendMessage(Component.text(this.isEndEvent()
                    ? "Recover void relics, raid survey caches, and claim rewards from /ce event."
                    : "Recover relics, hit milestones, and claim rewards from /ce event.", NamedTextColor.YELLOW));
            this.notifyNewRewards(player, null);
        }
        this.updateBossBarViewers();
    }

    public void handleQuit(Player player) {
        if (this.bossBar != null) {
            this.bossBar.removePlayer(player);
        }
    }

    public void handleWorldEntry(Player player) {
        if (!this.isLive() || player.getWorld().getEnvironment() != this.eventEnvironment()) {
            return;
        }
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        if (before.netherEntries() > 0) {
            return;
        }
        long now = System.currentTimeMillis();
        this.updateProgress(player.getUniqueId(), player.getName(), 0, 1, 0, 0, 0,
                now, before.firstRelicAt(), before.firstBlazeKillAt(), before.firstAncientDebrisAt(), before.firstCacheLootAt());
        this.tryBroadcastFirst("first_entry", this.isEndEvent() ? "First Into The End" : "First Through The Portal", player,
                this.isEndEvent() ? "was the first soul into the End." : "was the first soul into the Nether.");
        this.grantConfiguredRelics(player, SOURCE_ENTRY, "entry", this.isEndEvent() ? "First End entry" : "First Nether entry");
    }

    public void handleNetherEntry(Player player) {
        this.handleWorldEntry(player);
    }

    public void handleCache(Player player, Block block) {
        if (!this.isLive() || block == null || block.getWorld().getEnvironment() != this.eventEnvironment()) {
            return;
        }
        String cacheType = this.resolveCacheType(block);
        if (cacheType == null || !this.claimCache(player.getUniqueId(), block)) {
            return;
        }
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        long now = System.currentTimeMillis();
        this.updateProgress(player.getUniqueId(), player.getName(), 0, 0, 0, 0, 1,
                before.firstEntryAt(), before.firstRelicAt(), before.firstBlazeKillAt(), before.firstAncientDebrisAt(), now);
        this.tryBroadcastFirst("first_cache", this.isEndEvent() ? "First Survey Cache" : "First Nether Cache", player,
                this.isEndEvent() ? "cracked open the first End survey cache." : "cracked open the first Nether cache.");
        this.logEvent(player.getUniqueId(), player.getName(), SOURCE_CACHE, 0L, "Claimed " + cacheType + " cache");
        this.grantConfiguredRelics(player, SOURCE_CACHE, "caches." + cacheType, this.prettyKey(cacheType) + " cache");
        if (this.isEndEvent()) {
            this.grantConfiguredCraftMaterials(player, "caches." + cacheType, this.prettyKey(cacheType) + " cache");
        }
        this.notifyNewRewards(player, before);
    }

    public void handleNetherCache(Player player, Block block) {
        this.handleCache(player, block);
    }

    public void handleAncientDebris(Player player) {
        this.handleResourceNode(player, Material.ANCIENT_DEBRIS);
    }

    public void handleResourceNode(Player player, Material material) {
        if (!this.isLive() || player.getWorld().getEnvironment() != this.eventEnvironment()) {
            return;
        }
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        long debrisFirst = before.firstAncientDebrisAt() > 0L ? before.firstAncientDebrisAt() : System.currentTimeMillis();
        this.updateProgress(player.getUniqueId(), player.getName(), 0, 0, 0, 1, 0,
                before.firstEntryAt(), before.firstRelicAt(), before.firstBlazeKillAt(), debrisFirst, before.firstCacheLootAt());
        if (before.ancientDebris() == 0) {
            this.tryBroadcastFirst("first_resource", this.isEndEvent() ? "First Chorus Harvest" : "First Ancient Debris", player,
                    this.isEndEvent() ? "harvested the first chorus bloom." : "unearthed the first ancient debris.");
        }
        if (this.isEndEvent()) {
            this.grantConfiguredCraftMaterials(player, "resource." + material.name(), material.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        } else {
            this.grantConfiguredRelics(player, SOURCE_RESOURCE, "ancient-debris", "Ancient debris");
        }
        this.notifyNewRewards(player, before);
    }

    public void handleMobKill(Player player, EntityType type) {
        this.handleMobKill(player, type, false);
    }

    public void handleMobKill(Player player, EntityType type, boolean elite) {
        if (!this.isLive() || player.getWorld().getEnvironment() != this.eventEnvironment()) {
            return;
        }
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        if ((!this.isEndEvent() && type == EntityType.BLAZE) || (this.isEndEvent() && elite)) {
            long blazeFirst = before.firstBlazeKillAt() > 0L ? before.firstBlazeKillAt() : System.currentTimeMillis();
            this.updateProgress(player.getUniqueId(), player.getName(), 0, 0, 1, 0, 0,
                    before.firstEntryAt(), before.firstRelicAt(), blazeFirst, before.firstAncientDebrisAt(), before.firstCacheLootAt());
            if (before.blazeKills() == 0) {
                this.tryBroadcastFirst("first_combat", this.isEndEvent() ? "First Elite Kill" : "First Blaze Kill", player,
                        this.isEndEvent() ? "secured the first voidbound elite kill." : "secured the first blaze kill.");
            }
        }
        String pathPrefix = elite ? "elites." : "mobs.";
        this.grantConfiguredRelics(player, elite ? SOURCE_ELITE : SOURCE_MOB, pathPrefix + type.name(), type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (this.isEndEvent()) {
            this.grantConfiguredCraftMaterials(player, pathPrefix + type.name(), type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        this.notifyNewRewards(player, before);
    }

    public boolean scheduleFromInput(String input) {
        Long parsed = this.parseScheduleInput(input);
        if (parsed == null || parsed <= System.currentTimeMillis()) {
            return false;
        }
        this.state = (parsed - System.currentTimeMillis()) <= this.getCountdownThresholdMs() ? EventState.COUNTDOWN : EventState.SCHEDULED;
        this.resumeState = this.state;
        this.scheduledStartAt = parsed;
        this.liveAt = 0L;
        this.endAt = 0L;
        this.pausedRemainingMs = 0L;
        this.announcedCheckpoints.clear();
        this.plugin.getConfig().set(this.dimensionLockKey(), true);
        this.plugin.saveConfig();
        this.saveState();
        this.broadcast(Component.text(this.getTitle() + " scheduled for " + this.formatTimestamp(parsed) + ".", NamedTextColor.GOLD));
        return true;
    }

    public void forceStart() {
        this.startLive(true);
    }

    public boolean togglePause() {
        long now = System.currentTimeMillis();
        if (this.state == EventState.PAUSED) {
            if (this.resumeState == EventState.LIVE) {
                this.state = EventState.LIVE;
                this.endAt = now + this.pausedRemainingMs;
            } else {
                this.scheduledStartAt = now + this.pausedRemainingMs;
                this.state = this.pausedRemainingMs <= this.getCountdownThresholdMs() ? EventState.COUNTDOWN : EventState.SCHEDULED;
            }
            this.pausedRemainingMs = 0L;
            this.saveState();
            this.broadcast(Component.text(this.getTitle() + " has resumed.", NamedTextColor.GOLD));
            return true;
        }
        if (this.state == EventState.LIVE) {
            this.resumeState = EventState.LIVE;
            this.pausedRemainingMs = Math.max(0L, this.endAt - now);
        } else if (this.state.preLive()) {
            this.resumeState = this.state;
            this.pausedRemainingMs = Math.max(0L, this.scheduledStartAt - now);
        } else {
            return false;
        }
        this.state = EventState.PAUSED;
        this.saveState();
        this.broadcast(Component.text(this.getTitle() + " has been paused by staff.", NamedTextColor.YELLOW));
        return true;
    }

    public boolean endNow() {
        if (this.state == EventState.ENDED) {
            return false;
        }
        this.endEvent(true);
        return true;
    }

    public boolean extendDays(int days) {
        if (days <= 0) {
            return false;
        }
        long add = Duration.ofDays(days).toMillis();
        if (this.state == EventState.LIVE) {
            this.endAt += add;
        } else if (this.state == EventState.PAUSED && this.resumeState == EventState.LIVE) {
            this.pausedRemainingMs += add;
        } else {
            return false;
        }
        this.saveState();
        this.broadcast(Component.text(this.getTitle() + " has been extended by " + days + " day(s).", NamedTextColor.GOLD));
        return true;
    }

    public EventProgress getProgress(UUID playerId, String playerName) {
        return this.getProgress(this.getActiveEventKey(), playerId, playerName);
    }

    public EventProgress getProgress(String eventKey, UUID playerId, String playerName) {
        String sql = """
                SELECT relics, nether_entries, blaze_kills, ancient_debris, cache_loot,
                       first_entry_at, first_relic_at, first_blaze_kill_at, first_ancient_debris_at, first_cache_loot_at
                FROM event_player_progress
                WHERE event_key = ? AND player_uuid = ?
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, eventKey);
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new EventProgress(
                            playerId,
                            playerName,
                            resultSet.getLong("relics"),
                            resultSet.getInt("nether_entries"),
                            resultSet.getInt("blaze_kills"),
                            resultSet.getInt("ancient_debris"),
                            resultSet.getInt("cache_loot"),
                            resultSet.getLong("first_entry_at"),
                            resultSet.getLong("first_relic_at"),
                            resultSet.getLong("first_blaze_kill_at"),
                            resultSet.getLong("first_ancient_debris_at"),
                            resultSet.getLong("first_cache_loot_at")
                    );
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Progress load failed: " + exception.getMessage());
        }
        return new EventProgress(playerId, playerName, 0L, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);
    }

    public long getTotalRelics() {
        return this.getTotalRelics(this.getActiveEventKey());
    }

    public long getTotalRelics(String eventKey) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT COALESCE(SUM(relics), 0) FROM event_player_progress WHERE event_key = ?")) {
            statement.setString(1, eventKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Total relics query failed: " + exception.getMessage());
        }
        return 0L;
    }

    public int getParticipantCount() {
        return this.getParticipantCount(this.getActiveEventKey());
    }

    public int getParticipantCount(String eventKey) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT COUNT(*) FROM event_player_progress WHERE event_key = ? AND relics > 0")) {
            statement.setString(1, eventKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Participant count query failed: " + exception.getMessage());
        }
        return 0;
    }

    public List<CollectorEntry> getTopCollectors(int limit) {
        return this.getTopCollectors(this.getActiveEventKey(), limit);
    }

    public List<CollectorEntry> getTopCollectors(String eventKey, int limit) {
        List<CollectorEntry> collectors = new ArrayList<>();
        String sql = """
                SELECT player_name, relics
                FROM event_player_progress
                WHERE event_key = ? AND relics > 0
                ORDER BY relics DESC, first_relic_at ASC, player_name ASC
                LIMIT ?
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, eventKey);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    collectors.add(new CollectorEntry(resultSet.getString("player_name"), resultSet.getLong("relics")));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Top collectors query failed: " + exception.getMessage());
        }
        return collectors;
    }

    public List<MilestoneStatus> getMilestones() {
        return this.getMilestones(this.getActiveEventKey());
    }

    public List<MilestoneStatus> getMilestones(String eventKey) {
        List<MilestoneStatus> milestones = new ArrayList<>();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT milestone_key, display_name, target_amount, progress_amount, completed_at FROM event_milestones WHERE event_key = ? ORDER BY target_amount ASC")) {
            statement.setString(1, eventKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String key = resultSet.getString("milestone_key");
                    MilestoneReward reward = this.getMilestoneReward(eventKey, key);
                    milestones.add(new MilestoneStatus(
                            key,
                            resultSet.getString("display_name"),
                            resultSet.getInt("target_amount"),
                            resultSet.getLong("progress_amount"),
                            resultSet.getLong("completed_at"),
                            reward.money(),
                            reward.itemKey(),
                            reward.itemMaterial(),
                            reward.itemAmount(),
                            this.isMilestonePublic(key)
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Milestone query failed: " + exception.getMessage());
        }
        return milestones;
    }

    public List<FirstDiscovery> getFirstDiscoveries() {
        return this.getFirstDiscoveries(this.getActiveEventKey());
    }

    public List<FirstDiscovery> getFirstDiscoveries(String eventKey) {
        List<FirstDiscovery> firsts = new ArrayList<>();
        firsts.add(this.findFirst(eventKey, this.isEndEvent(eventKey) ? "First Into The End" : "First Through The Portal", "first_entry_at"));
        firsts.add(this.findFirst(eventKey, "First Relic Turn-In", "first_relic_at"));
        firsts.add(this.findFirst(eventKey, this.isEndEvent(eventKey) ? "First Elite Kill" : "First Blaze Kill", "first_blaze_kill_at"));
        firsts.add(this.findFirst(eventKey, this.isEndEvent(eventKey) ? "First Chorus Harvest" : "First Ancient Debris", "first_ancient_debris_at"));
        firsts.add(this.findFirst(eventKey, this.isEndEvent(eventKey) ? "First Survey Cache" : "First Nether Cache", "first_cache_loot_at"));
        return firsts.stream().filter(entry -> entry != null).toList();
    }

    public List<EventLogEntry> getRecentLogs(int limit) {
        return this.getRecentLogs(this.getActiveEventKey(), limit);
    }

    public List<EventLogEntry> getRecentLogs(String eventKey, int limit) {
        List<EventLogEntry> logs = new ArrayList<>();
        String sql = """
                SELECT player_name, source_type, amount, detail, recorded_at
                FROM event_relic_log
                WHERE event_key = ?
                ORDER BY recorded_at DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, eventKey);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    logs.add(new EventLogEntry(
                            resultSet.getString("player_name"),
                            resultSet.getString("source_type"),
                            resultSet.getLong("amount"),
                            resultSet.getString("detail"),
                            resultSet.getLong("recorded_at")
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Log query failed: " + exception.getMessage());
        }
        return logs;
    }

    public List<RewardStatus> getRewardStatuses(Player player) {
        EventProgress progress = this.getProgress(player.getUniqueId(), player.getName());
        return this.buildRewardStatuses(player, progress);
    }

    public List<RewardStatus> getRewardStatuses(String eventKey, Player player) {
        EventProgress progress = this.getProgress(eventKey, player.getUniqueId(), player.getName());
        return this.buildRewardStatuses(eventKey, player, progress);
    }

    public int turnInHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!this.itemFactory.isRelic(hand, this.getActiveEventKey())) {
            return 0;
        }
        return this.turnInRelics(player, List.of(hand), true);
    }

    public int turnInInventory(Player player) {
        List<ItemStack> relics = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (this.itemFactory.isRelic(item, this.getActiveEventKey())) {
                relics.add(item);
            }
        }
        return this.turnInRelics(player, relics, false);
    }

    public boolean claimReward(Player player, String rewardKey) {
        return this.claimReward(this.getActiveEventKey(), player, rewardKey);
    }

    public boolean claimReward(String eventKey, Player player, String rewardKey) {
        if (!this.getActiveEventKey().equalsIgnoreCase(eventKey)) {
            return false;
        }
        RewardStatus status = this.getRewardStatuses(player).stream()
                .filter(entry -> entry.key().equalsIgnoreCase(rewardKey))
                .findFirst()
                .orElse(null);
        if (status == null || status.claimed() || !status.claimable()) {
            return false;
        }
        if (status.money() > 0L) {
            this.plugin.getEconomy().deposit(player, status.money(), "event-rewards", this.getTitle() + ": " + status.display());
        }
        if (status.itemKey() != null) {
            ItemStack rewardItem = this.itemFactory.createRewardItem(this.getActiveEventKey(), status.itemKey());
            if (rewardItem != null) {
                this.giveItem(player, rewardItem);
            }
        } else if (status.itemMaterial() != null && status.itemAmount() > 0) {
            this.giveItem(player, new ItemStack(status.itemMaterial(), status.itemAmount()));
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO event_reward_claims (event_key, reward_key, player_uuid, player_name, claimed_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, rewardKey);
            statement.setString(3, player.getUniqueId().toString());
            statement.setString(4, player.getName());
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Reward claim save failed: " + exception.getMessage());
            return false;
        }
        for (String command : status.rewardCommands()) {
            this.dispatchRewardCommand(player, command);
        }
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "event_reward",
                "Claimed reward: " + status.display(),
                "You claimed your " + this.getTitle() + " reward.");
        this.logEvent(player.getUniqueId(), player.getName(), "reward_claim", 0L, status.display());
        return true;
    }

    public List<EventItemFactory.RelicDefinition> getRelicDefinitions() {
        return this.itemFactory.getRelics();
    }

    public List<EventItemFactory.CraftMaterialDefinition> getCraftMaterialDefinitions() {
        return this.itemFactory.getCraftMaterials();
    }

    public ItemStack createRelicPreview(String relicKey) {
        return this.itemFactory.createRelic(relicKey, 1);
    }

    public ItemStack createRewardPreview(RewardStatus reward) {
        return this.createRewardPreview(this.getActiveEventKey(), reward);
    }

    public ItemStack createRewardPreview(String eventKey, RewardStatus reward) {
        if (reward.itemKey() != null) {
            return this.itemFactory.createRewardItem(eventKey, reward.itemKey());
        }
        if (reward.itemMaterial() != null && reward.itemAmount() > 0) {
            return new ItemStack(reward.itemMaterial(), reward.itemAmount());
        }
        return new ItemStack(Material.GOLD_INGOT);
    }

    private int turnInRelics(Player player, List<ItemStack> relics, boolean handOnly) {
        if (!this.isEnabled()) {
            return 0;
        }
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        int points = 0;
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (ItemStack relic : relics) {
            if (!this.itemFactory.isRelic(relic, this.getActiveEventKey())) {
                continue;
            }
            String relicKey = this.itemFactory.getRelicKey(relic);
            int perItem = this.itemFactory.getRelicPoints(relic);
            if (relicKey == null || perItem <= 0 || relic.getAmount() <= 0) {
                continue;
            }
            int stackPoints = perItem * relic.getAmount();
            points += stackPoints;
            summary.merge(relicKey, relic.getAmount(), Integer::sum);
            relic.setAmount(0);
        }
        if (points <= 0) {
            return 0;
        }
        long firstRelicAt = before.firstRelicAt() > 0L ? before.firstRelicAt() : System.currentTimeMillis();
        this.updateProgress(player.getUniqueId(), player.getName(), points, 0, 0, 0, 0,
                before.firstEntryAt(), firstRelicAt, before.firstBlazeKillAt(), before.firstAncientDebrisAt(), before.firstCacheLootAt());
        this.incrementMilestones(points);
        String detail = this.describeTurnIn(summary, points);
        this.logEvent(player.getUniqueId(), player.getName(), "relic_turnin", points, detail);
        if (before.relics() == 0L) {
            this.tryBroadcastFirst("first_relic", "First Relic Turn-In", player, "made the first relic offering.");
        }
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "nether_turnin",
                "Relics turned in",
                "You turned in " + this.getTitle() + " relics worth " + points + " point(s).");
        player.sendActionBar(Component.text("Turned in +" + points + " relic points", NamedTextColor.GOLD));
        this.notifyNewRewards(player, before);
        if (handOnly) {
            player.getInventory().setItemInMainHand(null);
        }
        return points;
    }

    private String describeTurnIn(Map<String, Integer> summary, int points) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : summary.entrySet()) {
            EventItemFactory.RelicDefinition definition = this.itemFactory.getRelicDefinition(entry.getKey());
            String name = definition == null ? this.prettyKey(entry.getKey()) : definition.displayName();
            parts.add(entry.getValue() + "x " + name);
        }
        return "Turned in " + String.join(", ", parts) + " for " + points + " points";
    }

    private void giveItem(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack extra : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    private void dispatchRewardCommand(Player player, String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        String resolved = command.trim();
        if (resolved.startsWith("[valhalla]")) {
            resolved = resolved.substring("[valhalla]".length()).trim();
            if (Bukkit.getPluginManager().getPlugin("ValhallaMMO") == null
                    && Bukkit.getPluginManager().getPlugin("ValhallaMMOPremium") == null) {
                return;
            }
        }
        resolved = resolved
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%event_key%", this.getActiveEventKey());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
    }

    public boolean blocksVanillaUse(ItemStack item) {
        return this.itemFactory.blocksVanillaUse(item);
    }

    private void grantConfiguredRelics(Player player, String sourceType, String path, String detail) {
        List<RelicRoll> rolls = this.getRelicRolls(path);
        if (rolls.isEmpty()) {
            return;
        }
        List<String> awarded = new ArrayList<>();
        long awardedPoints = 0L;
        for (RelicRoll roll : rolls) {
            if (Math.random() > roll.chance()) {
                continue;
            }
            ItemStack item = this.itemFactory.createRelic(roll.relicKey(), roll.amount());
            if (item == null) {
                continue;
            }
            this.giveItem(player, item);
            EventItemFactory.RelicDefinition definition = this.itemFactory.getRelicDefinition(roll.relicKey());
            if (definition != null) {
                awarded.add(roll.amount() + "x " + definition.displayName());
                awardedPoints += (long) definition.points() * roll.amount();
            } else {
                awarded.add(roll.amount() + "x " + this.prettyKey(roll.relicKey()));
            }
        }
        if (awarded.isEmpty()) {
            return;
        }
        String awardedText = String.join(", ", awarded);
        player.sendMessage(Component.text("Recovered relics: " + awardedText, NamedTextColor.GOLD));
        player.sendActionBar(Component.text("Recovered relics: " + awardedText, NamedTextColor.RED));
        this.logEvent(player.getUniqueId(), player.getName(), sourceType, awardedPoints, detail + " -> " + awardedText);
    }

    private void grantConfiguredCraftMaterials(Player player, String path, String detail) {
        List<MaterialRoll> rolls = this.getCraftMaterialRolls(path);
        if (rolls.isEmpty()) {
            return;
        }
        List<String> awarded = new ArrayList<>();
        for (MaterialRoll roll : rolls) {
            if (Math.random() > roll.chance()) {
                continue;
            }
            ItemStack item = this.itemFactory.createCraftMaterial(roll.key(), roll.amount());
            if (item == null) {
                continue;
            }
            this.giveItem(player, item);
            EventItemFactory.CraftMaterialDefinition definition = this.itemFactory.getCraftMaterialDefinition(roll.key());
            awarded.add(roll.amount() + "x " + (definition == null ? this.prettyKey(roll.key()) : definition.displayName()));
        }
        if (awarded.isEmpty()) {
            return;
        }
        String awardedText = String.join(", ", awarded);
        player.sendActionBar(Component.text("Recovered materials: " + awardedText, NamedTextColor.AQUA));
        this.logEvent(player.getUniqueId(), player.getName(), SOURCE_CRAFT, 0L, detail + " -> " + awardedText);
    }

    private List<RelicRoll> getRelicRolls(String path) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection(this.eventRoot() + ".sources." + path);
        if (section == null) {
            return this.defaultRelicRolls(path);
        }
        List<RelicRoll> rolls = new ArrayList<>();
        ConfigurationSection rollsSection = section.getConfigurationSection("rolls");
        if (rollsSection != null) {
            for (String key : rollsSection.getKeys(false)) {
                ConfigurationSection roll = rollsSection.getConfigurationSection(key);
                if (roll != null) {
                    rolls.add(new RelicRoll(
                            roll.getString("relic", key),
                            Math.max(1, roll.getInt("amount", 1)),
                            Math.max(0.0, Math.min(1.0, roll.getDouble("chance", 1.0)))
                    ));
                }
            }
        }
        return rolls.isEmpty() ? this.defaultRelicRolls(path) : rolls;
    }

    private List<MaterialRoll> getCraftMaterialRolls(String path) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection(this.eventRoot() + ".sources.craft." + path);
        if (section == null) {
            return this.defaultCraftMaterialRolls(path);
        }
        List<MaterialRoll> rolls = new ArrayList<>();
        ConfigurationSection rollsSection = section.getConfigurationSection("rolls");
        if (rollsSection != null) {
            for (String key : rollsSection.getKeys(false)) {
                ConfigurationSection roll = rollsSection.getConfigurationSection(key);
                if (roll != null) {
                    rolls.add(new MaterialRoll(
                            roll.getString("material-key", key),
                            Math.max(1, roll.getInt("amount", 1)),
                            Math.max(0.0, Math.min(1.0, roll.getDouble("chance", 1.0)))
                    ));
                }
            }
        }
        return rolls.isEmpty() ? this.defaultCraftMaterialRolls(path) : rolls;
    }

    private List<RelicRoll> defaultRelicRolls(String path) {
        if (this.isEndEvent()) {
            return switch (path) {
                case "entry" -> List.of(new RelicRoll("echo_shard", 1, 1.0));
                case "caches.end_city" -> List.of(
                        new RelicRoll("shulker_sigil", 1, 0.55),
                        new RelicRoll("starchart_fragment", 1, 0.45),
                        new RelicRoll("void_core", 1, 0.12),
                        new RelicRoll("crown_of_the_void", 1, 0.03)
                );
                case "caches.generic" -> List.of(new RelicRoll("echo_shard", 1, 0.35));
                case "mobs.ENDERMAN" -> List.of(new RelicRoll("echo_shard", 1, 0.24));
                case "mobs.SHULKER" -> List.of(
                        new RelicRoll("shulker_sigil", 1, 0.30),
                        new RelicRoll("echo_shard", 1, 0.20)
                );
                case "mobs.ENDERMITE" -> List.of(new RelicRoll("echo_shard", 1, 0.12));
                case "elites.ENDERMAN" -> List.of(
                        new RelicRoll("void_core", 1, 0.35),
                        new RelicRoll("crown_of_the_void", 1, 0.04)
                );
                case "elites.SHULKER" -> List.of(
                        new RelicRoll("starchart_fragment", 1, 0.45),
                        new RelicRoll("void_core", 1, 0.25),
                        new RelicRoll("crown_of_the_void", 1, 0.05)
                );
                default -> List.of();
            };
        }
        return switch (path) {
            case "entry" -> List.of(new RelicRoll("ember_shard", 1, 1.0));
            case "ancient-debris" -> List.of(
                    new RelicRoll("ancient_core", 1, 0.30),
                    new RelicRoll("ember_shard", 1, 0.75),
                    new RelicRoll("crown_fragment", 1, 0.03)
            );
            case "caches.fortress" -> List.of(
                    new RelicRoll("blaze_sigil", 1, 0.55),
                    new RelicRoll("ember_shard", 1, 0.75),
                    new RelicRoll("ancient_core", 1, 0.12)
            );
            case "caches.bastion" -> List.of(
                    new RelicRoll("gilded_fang", 1, 0.60),
                    new RelicRoll("ember_shard", 1, 0.70),
                    new RelicRoll("ancient_core", 1, 0.18),
                    new RelicRoll("crown_fragment", 1, 0.05)
            );
            case "caches.ruined_portal" -> List.of(
                    new RelicRoll("ember_shard", 1, 0.85),
                    new RelicRoll("gilded_fang", 1, 0.20)
            );
            case "caches.generic" -> List.of(new RelicRoll("ember_shard", 1, 0.50));
            case "mobs.BLAZE" -> List.of(
                    new RelicRoll("blaze_sigil", 1, 0.35),
                    new RelicRoll("ember_shard", 1, 0.55)
            );
            case "mobs.WITHER_SKELETON" -> List.of(
                    new RelicRoll("blaze_sigil", 1, 0.25),
                    new RelicRoll("ember_shard", 1, 0.45)
            );
            case "mobs.GHAST" -> List.of(
                    new RelicRoll("ember_shard", 1, 0.30),
                    new RelicRoll("ancient_core", 1, 0.06)
            );
            case "mobs.MAGMA_CUBE" -> List.of(new RelicRoll("ember_shard", 1, 0.28));
            case "mobs.PIGLIN_BRUTE" -> List.of(
                    new RelicRoll("gilded_fang", 1, 0.45),
                    new RelicRoll("crown_fragment", 1, 0.03)
            );
            case "mobs.HOGLIN" -> List.of(new RelicRoll("ember_shard", 1, 0.25));
            default -> List.of();
        };
    }

    private List<MaterialRoll> defaultCraftMaterialRolls(String path) {
        if (!this.isEndEvent()) {
            return List.of();
        }
        return switch (path) {
            case "resource.CHORUS_FLOWER" -> List.of(
                    new MaterialRoll("void_filament", 1, 0.85),
                    new MaterialRoll("chorus_weave", 1, 0.35)
            );
            case "caches.end_city" -> List.of(
                    new MaterialRoll("gateway_residue", 1, 0.30),
                    new MaterialRoll("chorus_weave", 1, 0.40)
            );
            case "elites.ENDERMAN" -> List.of(
                    new MaterialRoll("gateway_residue", 1, 0.45),
                    new MaterialRoll("void_filament", 1, 0.40)
            );
            case "elites.SHULKER" -> List.of(
                    new MaterialRoll("chorus_weave", 1, 0.60),
                    new MaterialRoll("gateway_residue", 1, 0.25)
            );
            default -> List.of();
        };
    }

    private String resolveCacheType(Block block) {
        BlockState state = block.getState();
        if (state instanceof Lootable lootable) {
            LootTable lootTable = lootable.getLootTable();
            if (lootTable != null && lootTable.getKey() != null) {
                String key = lootTable.getKey().getKey().toLowerCase(Locale.ROOT);
                if (key.contains("bastion")) {
                    return "bastion";
                }
                if (key.contains("fortress")) {
                    return "fortress";
                }
                if (key.contains("ruined_portal")) {
                    return "ruined_portal";
                }
                if (key.contains("end_city")) {
                    return "end_city";
                }
            }
        }
        return this.isEndEvent() ? "generic" : "generic";
    }

    private boolean claimCache(UUID playerId, Block block) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR IGNORE INTO event_cache_claims (event_key, player_uuid, world_name, block_x, block_y, block_z, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, playerId.toString());
            statement.setString(3, block.getWorld().getName());
            statement.setInt(4, block.getX());
            statement.setInt(5, block.getY());
            statement.setInt(6, block.getZ());
            statement.setLong(7, System.currentTimeMillis());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Cache claim failed: " + exception.getMessage());
            return false;
        }
    }

    private void heartbeat() {
        if (!this.isEnabled()) {
            return;
        }
        this.pruneLiveMoments();
        long now = System.currentTimeMillis();
        if (this.state == EventState.PAUSED) {
            this.updateBossBarPaused();
            return;
        }
        if (this.state.preLive()) {
            if (this.scheduledStartAt > 0L) {
                long remaining = this.scheduledStartAt - now;
                EventState nextState = remaining <= this.getCountdownThresholdMs() ? EventState.COUNTDOWN : EventState.SCHEDULED;
                if (nextState != this.state) {
                    this.state = nextState;
                    this.saveState();
                }
                if (remaining <= 0L) {
                    this.startLive(false);
                    return;
                }
                this.checkCountdownBroadcasts(remaining);
                this.updateBossBarCountdown(remaining);
            }
            return;
        }
        if (this.state == EventState.LIVE) {
            if (this.endAt > 0L && now >= this.endAt) {
                this.endEvent(true);
                return;
            }
            this.updateBossBarLive();
            return;
        }
        this.clearBossBar();
    }

    private void startLive(boolean manual) {
        long now = System.currentTimeMillis();
        this.state = EventState.LIVE;
        this.resumeState = EventState.LIVE;
        this.liveAt = now;
        if (this.endAt <= now) {
            this.endAt = now + Duration.ofDays(this.getDurationDays()).toMillis();
        }
        this.pausedRemainingMs = 0L;
        this.plugin.getConfig().set(this.dimensionLockKey(), false);
        this.plugin.saveConfig();
        this.saveState();
        this.broadcast(Component.text(this.getTitle() + " has begun. " + this.getDimensionName() + " is now open!", NamedTextColor.RED));
        this.broadcast(Component.text(this.isEndEvent()
                ? "Recover void relics, gather craft materials, and claim rewards with /ce event."
                : "Recover relics, turn them in from /ce relics, and claim rewards with /ce event.", NamedTextColor.GOLD));
        if (this.isEndEvent()) {
            this.triggerLiveMoment("dragon-ceremony", "Dragon Ceremony",
                    "The End has opened. Rally at the central island and begin the first expedition.",
                    20L * 60L * 1000L, manual ? "Staff" : "System");
        }
        this.logEvent(null, null, "event_started", 0L, manual ? "Started manually" : "Countdown completed");
    }

    private void endEvent(boolean broadcast) {
        this.state = EventState.ENDED;
        this.resumeState = EventState.ENDED;
        this.pausedRemainingMs = 0L;
        this.liveMoments.clear();
        this.saveState();
        this.clearBossBar();
        if (broadcast) {
            this.broadcast(Component.text(this.getTitle() + " has ended. Final rewards are now locked in.", NamedTextColor.GOLD));
        }
        this.logEvent(null, null, "event_ended", 0L, "Opening Week ended");
    }

    private void ensureStateRow() {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR IGNORE INTO server_event_state (event_key, display_name, state, previous_state, scheduled_start_at, live_at, end_at, paused_remaining_ms, updated_at) VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?)")) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, this.getTitle());
            statement.setString(3, EventState.SCHEDULED.name());
            statement.setString(4, EventState.SCHEDULED.name());
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Event state init failed", exception);
        }
    }

    private void loadState() {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT state, previous_state, scheduled_start_at, live_at, end_at, paused_remaining_ms FROM server_event_state WHERE event_key = ?")) {
            statement.setString(1, this.getActiveEventKey());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    this.state = EventState.valueOf(resultSet.getString("state"));
                    this.resumeState = EventState.valueOf(resultSet.getString("previous_state"));
                    this.scheduledStartAt = resultSet.getLong("scheduled_start_at");
                    this.liveAt = resultSet.getLong("live_at");
                    this.endAt = resultSet.getLong("end_at");
                    this.pausedRemainingMs = resultSet.getLong("paused_remaining_ms");
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] State load failed: " + exception.getMessage());
        }
        this.seedCheckpointState();
    }

    private void saveState() {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE server_event_state SET display_name = ?, state = ?, previous_state = ?, scheduled_start_at = ?, live_at = ?, end_at = ?, paused_remaining_ms = ?, updated_at = ? WHERE event_key = ?")) {
            statement.setString(1, this.getTitle());
            statement.setString(2, this.state.name());
            statement.setString(3, this.resumeState.name());
            statement.setLong(4, this.scheduledStartAt);
            statement.setLong(5, this.liveAt);
            statement.setLong(6, this.endAt);
            statement.setLong(7, this.pausedRemainingMs);
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, this.getActiveEventKey());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] State save failed: " + exception.getMessage());
        }
    }

    private void seedStateFromConfigIfNeeded() {
        if (this.state != EventState.SCHEDULED || this.scheduledStartAt > 0L || this.liveAt > 0L || this.endAt > 0L) {
            return;
        }
        Long configured = this.parseScheduleInput(this.plugin.getConfig().getString(this.eventRoot() + ".start-time", ""));
        if (configured == null) {
            return;
        }
        if (configured <= System.currentTimeMillis()) {
            this.state = EventState.LIVE;
            this.resumeState = EventState.LIVE;
            this.liveAt = configured;
            this.endAt = configured + Duration.ofDays(this.getDurationDays()).toMillis();
            this.plugin.getConfig().set(this.dimensionLockKey(), false);
            this.plugin.saveConfig();
        } else {
            this.scheduledStartAt = configured;
            this.state = configured - System.currentTimeMillis() <= this.getCountdownThresholdMs() ? EventState.COUNTDOWN : EventState.SCHEDULED;
            this.resumeState = this.state;
        }
        this.saveState();
    }

    private void syncMilestones() {
        for (MilestoneConfig milestone : this.getConfiguredMilestones()) {
            try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                    """
                    INSERT INTO event_milestones (event_key, milestone_key, display_name, target_amount, progress_amount, completed_at)
                    VALUES (?, ?, ?, ?, 0, 0)
                    ON CONFLICT(event_key, milestone_key)
                    DO UPDATE SET display_name = excluded.display_name, target_amount = excluded.target_amount
                    """)) {
                statement.setString(1, this.getActiveEventKey());
                statement.setString(2, milestone.key());
                statement.setString(3, milestone.display());
                statement.setInt(4, milestone.target());
                statement.executeUpdate();
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[Events] Milestone sync failed: " + exception.getMessage());
            }
        }
    }

    private void updateProgress(UUID playerId, String playerName, long relicDelta, int entryDelta, int blazeDelta, int debrisDelta, int cacheDelta,
                                long firstEntryAt, long firstRelicAt, long firstBlazeKillAt, long firstAncientDebrisAt, long firstCacheLootAt) {
        EventProgress existing = this.getProgress(playerId, playerName);
        String sql = """
                INSERT INTO event_player_progress (
                    event_key, player_uuid, player_name, relics, nether_entries, blaze_kills, ancient_debris, cache_loot,
                    first_entry_at, first_relic_at, first_blaze_kill_at, first_ancient_debris_at, first_cache_loot_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_key, player_uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    relics = excluded.relics,
                    nether_entries = excluded.nether_entries,
                    blaze_kills = excluded.blaze_kills,
                    ancient_debris = excluded.ancient_debris,
                    cache_loot = excluded.cache_loot,
                    first_entry_at = excluded.first_entry_at,
                    first_relic_at = excluded.first_relic_at,
                    first_blaze_kill_at = excluded.first_blaze_kill_at,
                    first_ancient_debris_at = excluded.first_ancient_debris_at,
                    first_cache_loot_at = excluded.first_cache_loot_at
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, playerId.toString());
            statement.setString(3, playerName);
            statement.setLong(4, existing.relics() + relicDelta);
            statement.setInt(5, existing.netherEntries() + entryDelta);
            statement.setInt(6, existing.blazeKills() + blazeDelta);
            statement.setInt(7, existing.ancientDebris() + debrisDelta);
            statement.setInt(8, existing.cacheLoot() + cacheDelta);
            statement.setLong(9, firstEntryAt);
            statement.setLong(10, firstRelicAt);
            statement.setLong(11, firstBlazeKillAt);
            statement.setLong(12, firstAncientDebrisAt);
            statement.setLong(13, firstCacheLootAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Progress update failed: " + exception.getMessage());
        }
    }

    private void awardRelics(Player player, int amount, String sourceType, String detail) {
        EventProgress before = this.getProgress(player.getUniqueId(), player.getName());
        long firstRelicAt = before.firstRelicAt() > 0L ? before.firstRelicAt() : System.currentTimeMillis();
        this.updateProgress(player.getUniqueId(), player.getName(), amount, 0, 0, 0, 0,
                before.firstEntryAt(), firstRelicAt, before.firstBlazeKillAt(), before.firstAncientDebrisAt(), before.firstCacheLootAt());
        this.logEvent(player.getUniqueId(), player.getName(), sourceType, amount, detail);
        this.incrementMilestones(amount);
        player.sendMessage(Component.text(this.getMenuLabel() + " relics +" + amount + " (" + (before.relics() + amount) + " total)", NamedTextColor.GOLD));
        player.sendActionBar(Component.text("Relics +" + amount, NamedTextColor.RED));
        if (before.relics() == 0L) {
            this.tryBroadcastFirst("first_relic", "First Relic Found", player,
                    this.isEndEvent() ? "recovered the first End relic." : "recovered the first Nether relic.");
        }
        this.notifyNewRewards(player, before);
    }

    private void incrementMilestones(int amount) {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE event_milestones SET progress_amount = MIN(target_amount, progress_amount + ?) WHERE event_key = ? AND completed_at = 0")) {
            statement.setInt(1, amount);
            statement.setString(2, this.getActiveEventKey());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Milestone progress failed: " + exception.getMessage());
        }
        this.completeMilestones();
    }

    private void completeMilestones() {
        try (PreparedStatement query = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT milestone_key, display_name, target_amount FROM event_milestones WHERE event_key = ? AND completed_at = 0 AND progress_amount >= target_amount")) {
            query.setString(1, this.getActiveEventKey());
            try (ResultSet resultSet = query.executeQuery()) {
                while (resultSet.next()) {
                    String key = resultSet.getString("milestone_key");
                    String display = resultSet.getString("display_name");
                    try (PreparedStatement update = this.plugin.getDataManager().getConnection().prepareStatement(
                            "UPDATE event_milestones SET completed_at = ? WHERE event_key = ? AND milestone_key = ? AND completed_at = 0")) {
                        update.setLong(1, System.currentTimeMillis());
                        update.setString(2, this.getActiveEventKey());
                        update.setString(3, key);
                        if (update.executeUpdate() > 0) {
                            if (this.isMilestonePublic(key)) {
                                this.broadcast(Component.text("Milestone reached: " + display + " (" + resultSet.getInt("target_amount") + " relics)", NamedTextColor.GOLD));
                            }
                            this.logEvent(null, null, "milestone", 0L, display);
                        }
                    }
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Milestone completion failed: " + exception.getMessage());
        }
    }

    private void notifyNewRewards(Player player, EventProgress before) {
        Set<String> previousClaimable = new HashSet<>();
        if (before != null) {
            for (RewardStatus status : this.buildRewardStatuses(player, before)) {
                if (!status.claimed() && status.claimable()) {
                    previousClaimable.add(status.key());
                }
            }
        }
        for (RewardStatus status : this.getRewardStatuses(player)) {
            if (!status.claimed() && status.claimable() && !previousClaimable.contains(status.key())) {
                this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "event_reward_ready",
                        this.getMenuLabel() + " reward ready: " + status.display(),
                        "Open /ce event rewards to claim it.");
            }
        }
    }

    private void tryBroadcastFirst(String logKey, String title, Player player, String body) {
        if (this.hasFirstLog(logKey)) {
            return;
        }
        this.logEvent(player.getUniqueId(), player.getName(), logKey, 0L, title);
        this.broadcast(Component.text(title + ": " + player.getName() + " " + body, NamedTextColor.LIGHT_PURPLE));
    }

    private boolean hasFirstLog(String logKey) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT 1 FROM event_relic_log WHERE event_key = ? AND source_type = ? LIMIT 1")) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, logKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] First-log query failed: " + exception.getMessage());
        }
        return false;
    }

    private void logEvent(UUID playerId, String playerName, String sourceType, long amount, String detail) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO event_relic_log (event_key, player_uuid, player_name, source_type, amount, detail, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, this.getActiveEventKey());
            statement.setString(2, playerId == null ? null : playerId.toString());
            statement.setString(3, playerName);
            statement.setString(4, sourceType);
            statement.setLong(5, amount);
            statement.setString(6, detail);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Log insert failed: " + exception.getMessage());
        }
    }

    private void checkCountdownBroadcasts(long remainingMs) {
        for (Integer checkpoint : this.getCheckpointsMinutes()) {
            long checkpointMs = Duration.ofMinutes(checkpoint).toMillis();
            if (remainingMs <= checkpointMs && this.announcedCheckpoints.add(checkpoint)) {
                this.broadcast(Component.text(this.getCheckpointMessage(checkpoint, remainingMs), NamedTextColor.GOLD));
            }
        }
        if (remainingMs <= 10000L) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(Component.text(this.getDimensionName() + " opens in " + this.formatDuration(remainingMs), NamedTextColor.RED));
            }
        }
    }

    private void pruneLiveMoments() {
        long now = System.currentTimeMillis();
        this.liveMoments.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private void updateBossBarCountdown(long remainingMs) {
        this.ensureBossBar();
        this.bossBar.setTitle(this.getTitle() + " - opens in " + this.formatDuration(remainingMs));
        double progress = 1.0 - Math.min(1.0, (double) remainingMs / (double) this.getCountdownThresholdMs());
        this.bossBar.setProgress(Math.max(0.01, Math.min(1.0, progress)));
        this.bossBar.setColor(BarColor.RED);
        this.updateBossBarViewers();
    }

    private void updateBossBarLive() {
        this.ensureBossBar();
        long totalDuration = Duration.ofDays(this.getDurationDays()).toMillis();
        long remaining = this.getSeasonRemainingMs();
        this.bossBar.setTitle(this.getTitle() + " - " + this.getTotalRelics() + " relics recovered");
        double progress = totalDuration <= 0L ? 1.0 : Math.max(0.0, Math.min(1.0, (double) remaining / (double) totalDuration));
        this.bossBar.setProgress(progress);
        this.bossBar.setColor(BarColor.PURPLE);
        this.updateBossBarViewers();
    }

    private void updateBossBarPaused() {
        this.ensureBossBar();
        this.bossBar.setTitle(this.getTitle() + " - paused");
        this.bossBar.setProgress(1.0);
        this.bossBar.setColor(BarColor.YELLOW);
        this.updateBossBarViewers();
    }

    private void ensureBossBar() {
        if (this.bossBar == null) {
            this.bossBar = Bukkit.createBossBar(this.getTitle(), BarColor.RED, BarStyle.SEGMENTED_10);
        }
    }

    private void clearBossBar() {
        if (this.bossBar != null) {
            this.bossBar.removeAll();
        }
    }

    private void updateBossBarViewers() {
        if (this.bossBar == null) {
            return;
        }
        this.bossBar.removeAll();
        if (!(this.state == EventState.COUNTDOWN || this.state == EventState.LIVE || this.state == EventState.PAUSED)) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.bossBar.addPlayer(online);
        }
    }

    private void broadcast(Component message) {
        Bukkit.broadcast(message);
    }

    private void syncDimensionLock() {
        if ((this.state == EventState.LIVE || this.state == EventState.ENDED)
                && this.plugin.getConfig().getBoolean(this.dimensionLockKey(), true)) {
            this.plugin.getConfig().set(this.dimensionLockKey(), false);
            this.plugin.saveConfig();
        }
    }

    private void seedCheckpointState() {
        this.announcedCheckpoints.clear();
        if (!this.state.preLive() || this.scheduledStartAt <= 0L) {
            return;
        }
        long remaining = this.scheduledStartAt - System.currentTimeMillis();
        for (Integer checkpoint : this.getCheckpointsMinutes()) {
            if (remaining < Duration.ofMinutes(checkpoint).toMillis()) {
                this.announcedCheckpoints.add(checkpoint);
            }
        }
    }

    private List<Integer> getCheckpointsMinutes() {
        List<Integer> checkpoints = this.plugin.getConfig().getIntegerList(this.eventRoot() + ".broadcasts.checkpoints-minutes");
        if (checkpoints.isEmpty()) {
            return List.of(60, 10, 1);
        }
        return checkpoints.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    }

    private long getCountdownThresholdMs() {
        return Duration.ofMinutes(Math.max(60, this.getCheckpointsMinutes().stream().mapToInt(Integer::intValue).max().orElse(60))).toMillis();
    }

    private int getDurationDays() {
        return Math.max(1, this.plugin.getConfig().getInt(this.eventRoot() + ".duration-days", 7));
    }

    private String getCheckpointMessage(int checkpoint, long remainingMs) {
        String configured = this.plugin.getConfig().getString(this.eventRoot() + ".broadcasts.checkpoint-" + checkpoint, "");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return this.getDimensionName() + " opens in " + this.formatDuration(Math.min(remainingMs, Duration.ofMinutes(checkpoint).toMillis())) + ".";
    }

    private boolean isMilestonePublic(String key) {
        MilestoneConfig milestone = this.getConfiguredMilestones().stream()
                .filter(entry -> entry.key().equalsIgnoreCase(key))
                .findFirst()
                .orElse(null);
        return this.plugin.getConfig().getBoolean(this.eventRoot() + ".broadcasts.public-milestones", true)
                && (milestone == null || milestone.announce());
    }

    private MilestoneReward getMilestoneReward(String key) {
        return this.getMilestoneReward(this.getActiveEventKey(), key);
    }

    private MilestoneReward getMilestoneReward(String eventKey, String key) {
        MilestoneConfig milestone = this.getConfiguredMilestones(eventKey).stream()
                .filter(entry -> entry.key().equalsIgnoreCase(key))
                .findFirst()
                .orElse(null);
        if (milestone == null) {
            return new MilestoneReward(0L, null, null, 0);
        }
        return new MilestoneReward(
                milestone.money(),
                milestone.itemKey(),
                milestone.itemMaterial(),
                milestone.itemAmount()
        );
    }

    private List<ConfiguredReward> getConfiguredRewards() {
        return this.getConfiguredRewards(this.getActiveEventKey());
    }

    private List<ConfiguredReward> getConfiguredRewards(String eventKey) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("events." + eventKey + ".rewards");
        Map<String, ConfiguredReward> rewards = new LinkedHashMap<>(this.defaultRewards(eventKey));
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection reward = section.getConfigurationSection(key);
                if (reward == null) {
                    continue;
                }
                ConfiguredReward fallback = rewards.getOrDefault(key, new ConfiguredReward(
                        key, this.prettyKey(key), this.getTitle(eventKey) + " reward.", 0, 0, 0, 0, 0, false, "", 0L, null, null, 0, List.of()
                ));
                rewards.put(key, new ConfiguredReward(
                        key,
                        reward.getString("display", fallback.display()),
                        reward.getString("description", fallback.description()),
                        reward.getInt("min-relics", fallback.minRelics()),
                        reward.getInt("min-blaze-kills", fallback.minBlazeKills()),
                        reward.getInt("min-ancient-debris", fallback.minAncientDebris()),
                        reward.getInt("min-cache-loot", fallback.minCacheLoot()),
                        reward.getInt("min-rank", fallback.minRank()),
                        reward.getBoolean("requires-event-ended", fallback.requiresEventEnded()),
                        reward.getString("requires-milestone", fallback.requiredMilestone()),
                        reward.getLong("reward-money", fallback.money()),
                        reward.getString("reward-item-key", fallback.itemKey()),
                        this.parseMaterial(reward.getString("reward-item"), fallback.itemMaterial()),
                        Math.max(0, reward.getInt("reward-item-amount", fallback.itemAmount())),
                        reward.getStringList("reward-commands").isEmpty() ? fallback.rewardCommands() : reward.getStringList("reward-commands")
                ));
            }
        }
        return new ArrayList<>(rewards.values());
    }

    private List<MilestoneConfig> getConfiguredMilestones() {
        return this.getConfiguredMilestones(this.getActiveEventKey());
    }

    private List<MilestoneConfig> getConfiguredMilestones(String eventKey) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("events." + eventKey + ".milestones");
        Map<String, MilestoneConfig> milestones = new LinkedHashMap<>(this.defaultMilestones(eventKey));
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection milestone = section.getConfigurationSection(key);
                if (milestone == null) {
                    continue;
                }
                MilestoneConfig fallback = milestones.getOrDefault(key, new MilestoneConfig(key, this.prettyKey(key), 0, true, 0L, null, null, 0));
                milestones.put(key, new MilestoneConfig(
                        key,
                        milestone.getString("display", fallback.display()),
                        milestone.getInt("target", fallback.target()),
                        milestone.getBoolean("announce", fallback.announce()),
                        milestone.getLong("reward-money", fallback.money()),
                        milestone.getString("reward-item-key", fallback.itemKey()),
                        this.parseMaterial(milestone.getString("reward-item"), fallback.itemMaterial()),
                        Math.max(0, milestone.getInt("reward-item-amount", fallback.itemAmount()))
                ));
            }
        }
        return new ArrayList<>(milestones.values());
    }

    private Map<String, MilestoneConfig> defaultMilestones() {
        return this.defaultMilestones(this.getActiveEventKey());
    }

    private Map<String, MilestoneConfig> defaultMilestones(String eventKey) {
        if (this.isEndEvent(eventKey)) {
            Map<String, MilestoneConfig> milestones = new LinkedHashMap<>();
            milestones.put("signal_in_the_void", new MilestoneConfig("signal_in_the_void", "Signal In The Void", 50, true, 500L, null, Material.END_ROD, 8));
            milestones.put("charted_shore", new MilestoneConfig("charted_shore", "Charted Shore", 150, true, 900L, null, Material.PURPUR_BLOCK, 16));
            milestones.put("chorus_spindle", new MilestoneConfig("chorus_spindle", "Chorus Spindle", 300, true, 1500L, null, Material.END_STONE_BRICKS, 16));
            milestones.put("city_lights", new MilestoneConfig("city_lights", "City Lights", 500, true, 2200L, null, Material.ENDER_CHEST, 1));
            milestones.put("gateway_chain", new MilestoneConfig("gateway_chain", "Gateway Chain", 750, true, 3200L, null, Material.CHORUS_FLOWER, 8));
            milestones.put("crown_beyond_stars", new MilestoneConfig("crown_beyond_stars", "Crown Beyond Stars", 1000, true, 5000L, "crown_beyond_stars", null, 0));
            return milestones;
        }
        Map<String, MilestoneConfig> milestones = new LinkedHashMap<>();
        milestones.put("spark_in_the_dark", new MilestoneConfig("spark_in_the_dark", "Spark In The Dark", 50, true, 500L, null, Material.GLOWSTONE, 16));
        milestones.put("ember_path", new MilestoneConfig("ember_path", "Ember Path", 150, true, 900L, null, Material.MAGMA_BLOCK, 16));
        milestones.put("crimson_push", new MilestoneConfig("crimson_push", "Crimson Push", 300, true, 1500L, null, Material.MAGMA_CREAM, 12));
        milestones.put("fortress_break", new MilestoneConfig("fortress_break", "Fortress Break", 500, true, 2200L, null, Material.BLAZE_ROD, 8));
        milestones.put("bastion_fall", new MilestoneConfig("bastion_fall", "Bastion Fall", 750, true, 3200L, null, Material.GILDED_BLACKSTONE, 16));
        milestones.put("crown_over_fire", new MilestoneConfig("crown_over_fire", "Crown Over Fire", 1000, true, 5000L, "crown_of_cinders", null, 0));
        return milestones;
    }

    private Map<String, ConfiguredReward> defaultRewards() {
        return this.defaultRewards(this.getActiveEventKey());
    }

    private Map<String, ConfiguredReward> defaultRewards(String eventKey) {
        if (this.isEndEvent(eventKey)) {
            Map<String, ConfiguredReward> rewards = new LinkedHashMap<>();
            rewards.put("starchart_scout", new ConfiguredReward("starchart_scout", "Starchart Scout", "Turn in 10 relic points during Endfall Opening Week.",
                    10, 0, 0, 0, 0, false, "", 500L, "starchart_compass", null, 0, List.of()));
            rewards.put("void_runner", new ConfiguredReward("void_runner", "Void Runner", "Turn in 25 relic points during Endfall Opening Week.",
                    25, 0, 0, 0, 0, false, "", 900L, null, Material.END_ROD, 12, List.of()));
            rewards.put("elite_breaker", new ConfiguredReward("elite_breaker", "Elite Breaker", "Defeat 3 voidbound elites and turn in 50 relic points during the event.",
                    50, 3, 0, 0, 0, false, "", 1500L, "voidwalker_boots", null, 0, List.of()));
            rewards.put("chorus_weaver", new ConfiguredReward("chorus_weaver", "Chorus Weaver", "Harvest 8 chorus blooms and turn in 75 relic points during Endfall Opening Week.",
                    75, 0, 8, 0, 0, false, "", 2200L, "gateway_lantern", null, 0, List.of()));
            rewards.put("city_surveyor", new ConfiguredReward("city_surveyor", "City Surveyor", "Turn in 125 relic points and claim 4 End survey caches during Endfall Opening Week.",
                    125, 0, 0, 4, 0, false, "", 3000L, "chorus_satchel", null, 0, List.of()));
            rewards.put("top_collector", new ConfiguredReward("top_collector", "Top Collector", "Finish Endfall Opening Week in first place on relic points.",
                    0, 0, 0, 0, 1, true, "", 5000L, "endfall_trophy", null, 0, List.of()));
            return rewards;
        }
        Map<String, ConfiguredReward> rewards = new LinkedHashMap<>();
        rewards.put("opening_scout", new ConfiguredReward("opening_scout", "Opening Scout", "Turn in 10 relic points during Nether Opening Week.",
                10, 0, 0, 0, 0, false, "", 500L, "scouts_ember_lantern", null, 0, List.of()));
        rewards.put("ember_courier", new ConfiguredReward("ember_courier", "Ember Courier", "Turn in 25 relic points during Opening Week.",
                25, 0, 0, 0, 0, false, "", 900L, null, Material.CRYING_OBSIDIAN, 8, List.of()));
        rewards.put("blaze_runner", new ConfiguredReward("blaze_runner", "Blaze Runner", "Defeat 5 blazes and turn in 50 relic points during the event.",
                50, 5, 0, 0, 0, false, "", 1500L, "blazebound_bow", null, 0, List.of()));
        rewards.put("debris_hunter", new ConfiguredReward("debris_hunter", "Debris Hunter", "Mine 2 ancient debris and turn in 75 relic points during Opening Week.",
                75, 0, 2, 0, 0, false, "", 2200L, "ashwalker_boots", null, 0, List.of()));
        rewards.put("bastion_raider", new ConfiguredReward("bastion_raider", "Bastion Raider", "Turn in 125 relic points during Opening Week.",
                125, 0, 0, 0, 0, false, "", 3000L, "bastion_guard", null, 0, List.of()));
        rewards.put("top_collector", new ConfiguredReward("top_collector", "Top Collector", "Finish Opening Week in first place on relic points.",
                0, 0, 0, 0, 1, true, "", 5000L, "nether_opening_trophy", null, 0, List.of()));
        return rewards;
    }

    private List<RewardStatus> buildRewardStatuses(Player player, EventProgress progress) {
        return this.buildRewardStatuses(this.getActiveEventKey(), player, progress);
    }

    private List<RewardStatus> buildRewardStatuses(String eventKey, Player player, EventProgress progress) {
        List<RewardStatus> statuses = new ArrayList<>();
        for (ConfiguredReward reward : this.getConfiguredRewards(eventKey)) {
            boolean claimed = this.hasClaimed(eventKey, player.getUniqueId(), reward.key());
            RewardEligibility eligibility = this.isEligible(eventKey, progress, reward, player);
            statuses.add(new RewardStatus(reward.key(), reward.display(), reward.description(), claimed, eligibility.claimable(), eligibility.reason(),
                    reward.money(), reward.itemKey(), reward.itemMaterial(), reward.itemAmount(), this.describeRewardChecks(eventKey, progress, reward, player), reward.rewardCommands()));
        }
        for (MilestoneStatus milestone : this.getMilestones(eventKey)) {
            String key = "milestone:" + milestone.key();
            boolean claimed = this.hasClaimed(eventKey, player.getUniqueId(), key);
            boolean claimable = milestone.completedAt() > 0L && progress.relics() > 0
                    && (milestone.money() > 0L || milestone.itemKey() != null || milestone.itemMaterial() != null);
            String reason = claimable ? "Ready to claim." : (milestone.completedAt() > 0L ? "Recover at least one relic to claim milestone rewards." : "Milestone not completed yet.");
            statuses.add(new RewardStatus(key, milestone.displayName() + " Reward",
                    "Server milestone reward for " + milestone.displayName() + ".", claimed, claimable, reason,
                    milestone.money(), milestone.itemKey(), milestone.itemMaterial(), milestone.itemAmount(), this.describeMilestoneChecks(progress, milestone), List.of()));
        }
        statuses.sort(Comparator.comparing(RewardStatus::claimed).thenComparing(RewardStatus::display));
        return statuses;
    }

    private RewardEligibility isEligible(String eventKey, EventProgress progress, ConfiguredReward reward, Player player) {
        boolean activeEvent = this.getActiveEventKey().equalsIgnoreCase(eventKey);
        if (reward.requiresEventEnded() && (!activeEvent || this.state != EventState.ENDED)) {
            return new RewardEligibility(false, "Available after Opening Week ends.");
        }
        if (!reward.requiredMilestone().isBlank()) {
            MilestoneStatus milestone = this.getMilestones(eventKey).stream()
                    .filter(entry -> entry.key().equalsIgnoreCase(reward.requiredMilestone()))
                    .findFirst()
                    .orElse(null);
            if (milestone == null || milestone.completedAt() <= 0L) {
                return new RewardEligibility(false, "Locked until the " + this.prettyKey(reward.requiredMilestone()) + " milestone is reached.");
            }
        }
        if (progress.relics() < reward.minRelics()) {
            return new RewardEligibility(false, "Recover " + reward.minRelics() + " relic(s).");
        }
        if (progress.blazeKills() < reward.minBlazeKills()) {
            return new RewardEligibility(false, "Defeat " + reward.minBlazeKills() + " " + this.combatRequirementLabel() + ".");
        }
        if (progress.ancientDebris() < reward.minAncientDebris()) {
            return new RewardEligibility(false, this.resourceRequirementVerb() + " " + reward.minAncientDebris() + " " + this.resourceRequirementLabel() + ".");
        }
        if (progress.cacheLoot() < reward.minCacheLoot()) {
            return new RewardEligibility(false, "Claim " + reward.minCacheLoot() + " " + this.cacheRequirementLabel() + ".");
        }
        if (reward.minRank() > 0 && this.getRank(eventKey, player.getUniqueId()) > reward.minRank()) {
            return new RewardEligibility(false, "Finish Opening Week in the top " + reward.minRank() + ".");
        }
        return new RewardEligibility(true, "Ready to claim.");
    }

    private List<String> describeRewardChecks(String eventKey, EventProgress progress, ConfiguredReward reward, Player player) {
        List<String> lines = new ArrayList<>();
        if (reward.minRelics() > 0) {
            lines.add("Relic turn-ins: " + progress.relics() + "/" + reward.minRelics());
        }
        if (reward.minBlazeKills() > 0) {
            lines.add(this.combatRequirementDisplay() + ": " + progress.blazeKills() + "/" + reward.minBlazeKills());
        }
        if (reward.minAncientDebris() > 0) {
            lines.add(this.resourceRequirementDisplay() + ": " + progress.ancientDebris() + "/" + reward.minAncientDebris());
        }
        if (reward.minCacheLoot() > 0) {
            lines.add(this.cacheRequirementDisplay() + ": " + progress.cacheLoot() + "/" + reward.minCacheLoot());
        }
        if (!reward.requiredMilestone().isBlank()) {
            MilestoneStatus milestone = this.getMilestones(eventKey).stream()
                    .filter(entry -> entry.key().equalsIgnoreCase(reward.requiredMilestone()))
                    .findFirst()
                    .orElse(null);
            lines.add("Milestone: " + this.prettyKey(reward.requiredMilestone()) + " - " + ((milestone != null && milestone.completedAt() > 0L) ? "complete" : "locked"));
        }
        if (reward.requiresEventEnded()) {
            lines.add("Event ended: " + ((this.getActiveEventKey().equalsIgnoreCase(eventKey) && this.state == EventState.ENDED) ? "yes" : "no"));
        }
        if (reward.minRank() > 0) {
            int rank = this.getRank(eventKey, player.getUniqueId());
            String current = rank == Integer.MAX_VALUE ? "unranked" : ("#" + rank);
            lines.add("Ranking: " + current + " (need top " + reward.minRank() + ")");
        }
        if (lines.isEmpty()) {
            lines.add("No extra requirements.");
        }
        return lines;
    }

    private List<String> describeMilestoneChecks(EventProgress progress, MilestoneStatus milestone) {
        List<String> lines = new ArrayList<>();
        lines.add("Server milestone: " + milestone.progress() + "/" + milestone.target());
        lines.add("Your relic turn-ins: " + progress.relics() + "/1");
        if (milestone.completedAt() > 0L) {
            lines.add("Status: unlocked");
        }
        return lines;
    }

    private int getRank(UUID playerId) {
        return this.getRank(this.getActiveEventKey(), playerId);
    }

    private int getRank(String eventKey, UUID playerId) {
        int rank = 1;
        for (CollectorEntry collector : this.getTopCollectors(eventKey, 100)) {
            UUID uuid = this.plugin.getDataManager().findUuidByName(collector.name());
            if (uuid != null && uuid.equals(playerId)) {
                return rank;
            }
            rank++;
        }
        return Integer.MAX_VALUE;
    }

    private boolean hasClaimed(UUID playerId, String rewardKey) {
        return this.hasClaimed(this.getActiveEventKey(), playerId, rewardKey);
    }

    private boolean hasClaimed(String eventKey, UUID playerId, String rewardKey) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT 1 FROM event_reward_claims WHERE event_key = ? AND reward_key = ? AND player_uuid = ? LIMIT 1")) {
            statement.setString(1, eventKey);
            statement.setString(2, rewardKey);
            statement.setString(3, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] Reward-claim lookup failed: " + exception.getMessage());
        }
        return false;
    }

    private FirstDiscovery findFirst(String label, String column) {
        return this.findFirst(this.getActiveEventKey(), label, column);
    }

    private FirstDiscovery findFirst(String eventKey, String label, String column) {
        String sql = "SELECT player_name, " + column + " AS discovered_at FROM event_player_progress WHERE event_key = ? AND " + column + " > 0 ORDER BY " + column + " ASC LIMIT 1";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, eventKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new FirstDiscovery(label, resultSet.getString("player_name"), resultSet.getLong("discovered_at"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Events] First-discovery query failed: " + exception.getMessage());
        }
        return null;
    }

    private Long parseScheduleInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        long now = System.currentTimeMillis();
        if (trimmed.matches("\\d+[mM]")) {
            return now + Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1))).toMillis();
        }
        if (trimmed.matches("\\d+[hH]")) {
            return now + Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1))).toMillis();
        }
        if (trimmed.matches("\\d+[dD]")) {
            return now + Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1))).toMillis();
        }
        if (trimmed.matches("\\d{10,13}")) {
            long raw = Long.parseLong(trimmed);
            return trimmed.length() == 10 ? raw * 1000L : raw;
        }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME, COMMAND_TIME, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(trimmed, formatter);
                return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private Material parseMaterial(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        return Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
    }

    private Material parseMaterial(String materialName, Material fallback) {
        Material material = this.parseMaterial(materialName);
        return material == null ? fallback : material;
    }

    private String prettyKey(String key) {
        String[] parts = key.replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String combatRequirementDisplay() {
        return this.isEndEvent() ? "Elite kills" : "Blaze kills";
    }

    private String combatRequirementLabel() {
        return this.isEndEvent() ? "voidbound elite(s)" : "blaze(s)";
    }

    private String resourceRequirementDisplay() {
        return this.isEndEvent() ? "Chorus blooms" : "Ancient debris";
    }

    private String resourceRequirementLabel() {
        return this.isEndEvent() ? "chorus bloom(s)" : "ancient debris";
    }

    private String resourceRequirementVerb() {
        return this.isEndEvent() ? "Harvest" : "Mine";
    }

    private String cacheRequirementDisplay() {
        return this.isEndEvent() ? "Survey caches" : "Caches cracked";
    }

    private String cacheRequirementLabel() {
        return this.isEndEvent() ? "End survey cache(s)" : "Nether cache(s)";
    }

    public record EventProgress(UUID playerId, String playerName, long relics, int netherEntries, int blazeKills,
                                int ancientDebris, int cacheLoot, long firstEntryAt, long firstRelicAt,
                                long firstBlazeKillAt, long firstAncientDebrisAt, long firstCacheLootAt) {
    }

    public record CollectorEntry(String name, long relics) {
    }

    public record MilestoneStatus(String key, String displayName, int target, long progress, long completedAt,
                                  long money, String itemKey, Material itemMaterial, int itemAmount, boolean publicAnnouncement) {
    }

    public record FirstDiscovery(String label, String playerName, long discoveredAt) {
    }

    public record EventLogEntry(String playerName, String type, long amount, String detail, long recordedAt) {
    }

    public record LiveMoment(String key, String label, String detail, long startedAt, long expiresAt, String startedBy) {
    }

    public record RewardStatus(String key, String display, String description, boolean claimed, boolean claimable,
                               String requirement, long money, String itemKey, Material itemMaterial, int itemAmount,
                               List<String> checks, List<String> rewardCommands) {
    }

    private record MilestoneReward(long money, String itemKey, Material itemMaterial, int itemAmount) {
    }

    private record RewardEligibility(boolean claimable, String reason) {
    }

    private record RelicRoll(String relicKey, int amount, double chance) {
    }

    private record MaterialRoll(String key, int amount, double chance) {
    }

    private record MilestoneConfig(String key, String display, int target, boolean announce, long money,
                                   String itemKey, Material itemMaterial, int itemAmount) {
    }

    private record ConfiguredReward(String key, String display, String description, int minRelics, int minBlazeKills,
                                    int minAncientDebris, int minCacheLoot, int minRank, boolean requiresEventEnded,
                                    String requiredMilestone, long money, String itemKey, Material itemMaterial, int itemAmount,
                                    List<String> rewardCommands) {
    }
}
