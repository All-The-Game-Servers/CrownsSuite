package com.xkstudios.crowns.analytics;

import com.xkstudios.crowns.CrownsPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EconomyLedgerManager {
    public static final String SOURCE = "source";
    public static final String SINK = "sink";
    private final CrownsPlugin plugin;

    public EconomyLedgerManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("analytics.economy.enabled", true);
    }

    public void pruneOldEntries() {
        if (!this.isEnabled()) {
            return;
        }
        int retentionDays = Math.max(1, this.plugin.getConfig().getInt("analytics.economy.retention-days", 90));
        long cutoff = System.currentTimeMillis() - retentionDays * 86400000L;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_ledger WHERE recorded_at < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Ledger] Prune failed: " + exception.getMessage());
        }
    }

    public void recordSource(UUID playerUuid, String playerName, String category, long amount, String detail) {
        this.record(playerUuid, playerName, category, SOURCE, amount, detail);
    }

    public void recordSink(UUID playerUuid, String playerName, String category, long amount, String detail) {
        this.record(playerUuid, playerName, category, SINK, amount, detail);
    }

    public void recordServerSink(String category, long amount, String detail) {
        this.record(null, "Server", category, SINK, amount, detail);
    }

    public EconomyLedgerSummary getSummary(PlaytimePeriod period) {
        Map<String, Long> sources = new LinkedHashMap<>();
        Map<String, Long> sinks = new LinkedHashMap<>();
        long totalSources = 0L;
        long totalSinks = 0L;
        String sql = "SELECT category, direction, SUM(amount) AS total FROM economy_ledger WHERE recorded_at >= ? GROUP BY category, direction";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setLong(1, this.periodStart(period));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String category = resultSet.getString("category");
                    String direction = resultSet.getString("direction");
                    long total = resultSet.getLong("total");
                    if (SOURCE.equals(direction)) {
                        sources.put(category, total);
                        totalSources += total;
                    } else {
                        sinks.put(category, total);
                        totalSinks += total;
                    }
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Ledger] Summary failed: " + exception.getMessage());
        }
        return new EconomyLedgerSummary(this.sortDescending(sources), this.sortDescending(sinks), totalSources, totalSinks);
    }

    public List<EconomyLedgerEntry> getRecentEntries(int limit) {
        List<EconomyLedgerEntry> entries = new ArrayList<>();
        String sql = "SELECT recorded_at, category, direction, amount, player_name, detail FROM economy_ledger ORDER BY recorded_at DESC LIMIT ?";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new EconomyLedgerEntry(
                            resultSet.getLong("recorded_at"),
                            resultSet.getString("category"),
                            resultSet.getString("direction"),
                            resultSet.getLong("amount"),
                            resultSet.getString("player_name"),
                            resultSet.getString("detail")
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Ledger] Recent query failed: " + exception.getMessage());
        }
        return entries;
    }

    private void record(UUID playerUuid, String playerName, String category, String direction, long amount, String detail) {
        if (!this.isEnabled() || amount <= 0L) {
            return;
        }
        String sql = """
                INSERT INTO economy_ledger (player_uuid, player_name, category, direction, amount, detail, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid != null ? playerUuid.toString() : null);
            statement.setString(2, playerName);
            statement.setString(3, category);
            statement.setString(4, direction);
            statement.setLong(5, amount);
            statement.setString(6, detail);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Ledger] Record failed: " + exception.getMessage());
        }
    }

    private long periodStart(PlaytimePeriod period) {
        return switch (period) {
            case TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            case DAYS_7 -> System.currentTimeMillis() - 7L * 86400000L;
            case DAYS_30 -> System.currentTimeMillis() - 30L * 86400000L;
            case ALL -> 0L;
        };
    }

    private Map<String, Long> sortDescending(Map<String, Long> values) {
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }
}
