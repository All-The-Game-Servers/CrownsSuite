package com.xkstudios.crowns.analytics;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.data.PlayerData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PlaytimeManager {
    private final CrownsPlugin plugin;
    private final Map<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAccountedTimes = new ConcurrentHashMap<>();
    private final ZoneId zoneId = ZoneId.systemDefault();

    public PlaytimeManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void handleJoin(Player player) {
        long now = System.currentTimeMillis();
        PlayerData data = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        if (data.getFirstJoinAt() <= 0L) {
            data.setFirstJoinAt(now);
        }
        data.setLastJoinAt(now);
        this.sessionStartTimes.put(player.getUniqueId(), now);
        this.lastAccountedTimes.put(player.getUniqueId(), now);
    }

    public void handleQuit(Player player) {
        this.closeSession(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    public void flushActiveSessions() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.accountDelta(player.getUniqueId(), player.getName(), now);
        }
    }

    public void closeAllSessions() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(this.sessionStartTimes.keySet())) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            this.closeSession(uuid, offlinePlayer.getName(), now);
        }
    }

    public long getCurrentSessionSeconds(UUID uuid) {
        Long start = this.sessionStartTimes.get(uuid);
        if (start == null) {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - start) / 1000L);
    }

    public Optional<PlaytimeSnapshot> getSnapshotByName(String name) {
        UUID uuid = this.plugin.getDataManager().findUuidByName(name);
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.of(this.getSnapshot(uuid));
    }

    public PlaytimeSnapshot getSnapshot(UUID uuid) {
        this.flushActiveSessions();
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String fallbackName = offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString();
        PlayerData data = this.plugin.getDataManager().getExistingOrCreate(uuid, fallbackName);
        return new PlaytimeSnapshot(
                uuid,
                data.getName(),
                data.getTotalPlaytimeSeconds(),
                this.getCurrentSessionSeconds(uuid),
                this.getPeriodSeconds(uuid, PlaytimePeriod.TODAY),
                this.getPeriodSeconds(uuid, PlaytimePeriod.DAYS_7),
                this.getPeriodSeconds(uuid, PlaytimePeriod.DAYS_30),
                data.getFirstJoinAt(),
                data.getLastJoinAt(),
                data.getLastQuitAt(),
                Bukkit.getPlayer(uuid) != null
        );
    }

    public List<PlaytimeEntry> getTopEntries(PlaytimePeriod period, int limit) {
        this.flushActiveSessions();
        List<PlaytimeEntry> entries = new ArrayList<>();
        if (period == PlaytimePeriod.ALL) {
            try (PreparedStatement statement = this.connection().prepareStatement(
                    "SELECT uuid, name, total_playtime_seconds FROM players ORDER BY total_playtime_seconds DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new PlaytimeEntry(
                                UUID.fromString(resultSet.getString("uuid")),
                                resultSet.getString("name"),
                                resultSet.getLong("total_playtime_seconds")
                        ));
                    }
                }
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[Analytics] Top all-time query failed: " + exception.getMessage());
            }
        } else {
            String sql = """
                    SELECT p.uuid, p.name, COALESCE(SUM(d.seconds_played), 0) AS seconds_played
                    FROM players p
                    LEFT JOIN playtime_daily d
                        ON p.uuid = d.player_uuid
                       AND d.play_date >= ?
                    GROUP BY p.uuid, p.name
                    ORDER BY seconds_played DESC
                    LIMIT ?
                    """;
            try (PreparedStatement statement = this.connection().prepareStatement(sql)) {
                statement.setString(1, this.cutoffDate(period).toString());
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new PlaytimeEntry(
                                UUID.fromString(resultSet.getString("uuid")),
                                resultSet.getString("name"),
                                resultSet.getLong("seconds_played")
                        ));
                    }
                }
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[Analytics] Top period query failed: " + exception.getMessage());
            }
        }
        entries.removeIf(entry -> entry.seconds() <= 0L);
        entries.sort(Comparator.comparingLong(PlaytimeEntry::seconds).reversed());
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    public List<PlaytimeEntry> getActiveSessionLeaders(int limit) {
        List<PlaytimeEntry> entries = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            entries.add(new PlaytimeEntry(player.getUniqueId(), player.getName(), this.getCurrentSessionSeconds(player.getUniqueId())));
        }
        entries.sort(Comparator.comparingLong(PlaytimeEntry::seconds).reversed());
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    public Map<LocalDate, Long> getDailyBreakdown(UUID uuid, int days) {
        this.flushActiveSessions();
        Map<LocalDate, Long> breakdown = new LinkedHashMap<>();
        LocalDate start = LocalDate.now(this.zoneId).minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            breakdown.put(date, 0L);
        }

        try (PreparedStatement statement = this.connection().prepareStatement(
                "SELECT play_date, seconds_played FROM playtime_daily WHERE player_uuid = ? AND play_date >= ? ORDER BY play_date ASC")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, start.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    breakdown.put(LocalDate.parse(resultSet.getString("play_date")), resultSet.getLong("seconds_played"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Analytics] Daily breakdown failed: " + exception.getMessage());
        }
        return breakdown;
    }

    private void closeSession(UUID uuid, String fallbackName, long endedAt) {
        Long sessionStart = this.sessionStartTimes.remove(uuid);
        if (sessionStart == null) {
            this.lastAccountedTimes.remove(uuid);
            this.plugin.getDataManager().getExistingOrCreate(uuid, fallbackName == null ? uuid.toString() : fallbackName).setLastQuitAt(endedAt);
            return;
        }

        this.accountDelta(uuid, fallbackName, endedAt);
        this.lastAccountedTimes.remove(uuid);
        PlayerData data = this.plugin.getDataManager().getExistingOrCreate(uuid, fallbackName == null ? uuid.toString() : fallbackName);
        data.setLastQuitAt(endedAt);
        long durationSeconds = Math.max(0L, (endedAt - sessionStart) / 1000L);
        try (PreparedStatement statement = this.connection().prepareStatement(
                "INSERT INTO playtime_sessions (player_uuid, session_start, session_end, duration_seconds) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, sessionStart);
            statement.setLong(3, endedAt);
            statement.setLong(4, durationSeconds);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Analytics] Session write failed: " + exception.getMessage());
        }
    }

    private void accountDelta(UUID uuid, String fallbackName, long now) {
        Long lastAccounted = this.lastAccountedTimes.get(uuid);
        if (lastAccounted == null) {
            this.lastAccountedTimes.put(uuid, now);
            return;
        }

        long deltaMillis = Math.max(0L, now - lastAccounted);
        if (deltaMillis < 1000L) {
            return;
        }

        PlayerData data = this.plugin.getDataManager().getExistingOrCreate(uuid, fallbackName == null ? uuid.toString() : fallbackName);
        data.addPlaytimeSeconds(deltaMillis / 1000L);
        this.upsertDailySlices(uuid, lastAccounted, now);
        this.lastAccountedTimes.put(uuid, now);
    }

    private void upsertDailySlices(UUID uuid, long startMillis, long endMillis) {
        if (endMillis <= startMillis) {
            return;
        }

        Map<LocalDate, Long> slices = new HashMap<>();
        long cursor = startMillis;
        while (cursor < endMillis) {
            LocalDate date = Instant.ofEpochMilli(cursor).atZone(this.zoneId).toLocalDate();
            long nextDay = date.plusDays(1).atStartOfDay(this.zoneId).toInstant().toEpochMilli();
            long sliceEnd = Math.min(endMillis, nextDay);
            long seconds = Math.max(0L, (sliceEnd - cursor) / 1000L);
            if (seconds > 0L) {
                slices.merge(date, seconds, Long::sum);
            }
            cursor = sliceEnd;
        }

        String sql = """
                INSERT INTO playtime_daily (player_uuid, play_date, seconds_played)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid, play_date)
                DO UPDATE SET seconds_played = seconds_played + excluded.seconds_played
                """;
        for (Map.Entry<LocalDate, Long> entry : slices.entrySet()) {
            try (PreparedStatement statement = this.connection().prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, entry.getKey().toString());
                statement.setLong(3, entry.getValue());
                statement.executeUpdate();
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[Analytics] Daily slice write failed: " + exception.getMessage());
            }
        }
    }

    private long getPeriodSeconds(UUID uuid, PlaytimePeriod period) {
        if (period == PlaytimePeriod.ALL) {
            return this.plugin.getDataManager().getExistingOrCreate(uuid, uuid.toString()).getTotalPlaytimeSeconds();
        }

        try (PreparedStatement statement = this.connection().prepareStatement(
                "SELECT COALESCE(SUM(seconds_played), 0) FROM playtime_daily WHERE player_uuid = ? AND play_date >= ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, this.cutoffDate(period).toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Analytics] Period query failed: " + exception.getMessage());
        }
        return 0L;
    }

    private LocalDate cutoffDate(PlaytimePeriod period) {
        LocalDate today = LocalDate.now(this.zoneId);
        if (period == PlaytimePeriod.TODAY) {
            return today;
        }
        return today.minusDays(period.dayWindow() - 1L);
    }

    private Connection connection() {
        return this.plugin.getDataManager().getConnection();
    }
}
