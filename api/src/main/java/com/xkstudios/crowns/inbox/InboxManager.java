package com.xkstudios.crowns.inbox;

import com.xkstudios.crowns.api.InboxProvider;
import com.xkstudios.crowns.data.DataManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class InboxManager implements InboxProvider {
    private final JavaPlugin plugin;
    private final DataManager dataManager;

    public InboxManager(JavaPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("inbox.enabled", true);
    }

    public void push(UUID playerUuid, String playerName, String type, String title, String body) {
        if (playerUuid == null) {
            return;
        }
        if (this.plugin.getConfig().getBoolean("inbox.live-chat-notifications", true)) {
            Player online = Bukkit.getPlayer(playerUuid);
            if (online != null) {
                online.sendMessage(Component.text(title, NamedTextColor.GOLD));
                if (body != null && !body.isBlank()) {
                    online.sendMessage(Component.text(body, NamedTextColor.GRAY));
                }
            }
        }
        if (!this.isEnabled()) {
            return;
        }
        String sql = """
                INSERT INTO player_inbox (player_uuid, player_name, type, title, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, type);
            statement.setString(4, title);
            statement.setString(5, body);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
            this.trim(playerUuid);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Insert failed: " + exception.getMessage());
        }
    }

    public List<InboxEntry> getEntries(UUID playerUuid, int limit) {
        List<InboxEntry> entries = new ArrayList<>();
        if (playerUuid == null) {
            return entries;
        }
        String sql = """
                SELECT id, type, title, body, created_at, read_at
                FROM player_inbox
                WHERE player_uuid = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new InboxEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("type"),
                            resultSet.getString("title"),
                            resultSet.getString("body"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("read_at") <= 0L
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Query failed: " + exception.getMessage());
        }
        return entries;
    }

    public List<InboxEntry> getRecentEntries(int limit) {
        List<InboxEntry> entries = new ArrayList<>();
        String sql = """
                SELECT id, type, title, body, created_at, read_at
                FROM player_inbox
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new InboxEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("type"),
                            resultSet.getString("title"),
                            resultSet.getString("body"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("read_at") <= 0L
                    ));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Recent query failed: " + exception.getMessage());
        }
        return entries;
    }

    public int getUnreadCount(UUID playerUuid) {
        if (playerUuid == null) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM player_inbox WHERE player_uuid = ? AND (read_at IS NULL OR read_at = 0)";
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Unread count failed: " + exception.getMessage());
        }
        return 0;
    }

    public void markRead(UUID playerUuid, long id) {
        if (playerUuid == null) {
            return;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "UPDATE player_inbox SET read_at = ? WHERE player_uuid = ? AND id = ?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerUuid.toString());
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Mark read failed: " + exception.getMessage());
        }
    }

    public void markAllRead(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "UPDATE player_inbox SET read_at = ? WHERE player_uuid = ? AND (read_at IS NULL OR read_at = 0)")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerUuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Mark all read failed: " + exception.getMessage());
        }
    }

    private void trim(UUID playerUuid) {
        int maxEntries = Math.max(10, this.plugin.getConfig().getInt("inbox.max-per-player", 50));
        String sql = """
                DELETE FROM player_inbox
                WHERE player_uuid = ?
                AND id NOT IN (
                    SELECT id FROM player_inbox WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?
                )
                """;
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerUuid.toString());
            statement.setInt(3, maxEntries);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Inbox] Trim failed: " + exception.getMessage());
        }
    }

    @Override
    public void sendNotification(UUID player, String title, String message) {
        this.push(player, "", "notification", title, message);
    }
}
