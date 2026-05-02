package com.xkstudios.crowns.mmo.social;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.data.DataManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class MmoGuildManager {
    private final CrownsPlugin plugin;
    private final DataManager dataManager;

    public MmoGuildManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
    }

    public void initialize() {
        this.ensureTables();
    }

    public boolean create(Player owner, String name, String tag) {
        if (!this.plugin.getConfig().getBoolean("mmo.guilds.enabled", true)) {
            owner.sendMessage(Component.text("Guilds are disabled.", NamedTextColor.RED));
            return false;
        }
        if (this.getGuildForPlayer(owner.getUniqueId()) != null) {
            owner.sendMessage(Component.text("You are already in a guild.", NamedTextColor.YELLOW));
            return false;
        }
        if (!this.validName(name) || !this.validTag(tag)) {
            owner.sendMessage(Component.text("Guild name or tag length is invalid.", NamedTextColor.RED));
            return false;
        }
        if (this.guildExists(name, tag)) {
            owner.sendMessage(Component.text("That guild name or tag is already taken.", NamedTextColor.RED));
            return false;
        }
        String guildId = UUID.randomUUID().toString();
        try (PreparedStatement guild = this.dataManager.getConnection().prepareStatement("""
                INSERT INTO mmo_guilds (guild_id, name, tag, owner_uuid, motd, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement member = this.dataManager.getConnection().prepareStatement("""
                INSERT INTO mmo_guild_members (guild_id, player_uuid, player_name, rank, joined_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            guild.setString(1, guildId);
            guild.setString(2, name);
            guild.setString(3, tag.toUpperCase(Locale.ROOT));
            guild.setString(4, owner.getUniqueId().toString());
            guild.setString(5, "Welcome to " + name + ".");
            guild.setLong(6, System.currentTimeMillis());
            guild.executeUpdate();
            member.setString(1, guildId);
            member.setString(2, owner.getUniqueId().toString());
            member.setString(3, owner.getName());
            member.setString(4, GuildRank.OWNER.name());
            member.setLong(5, System.currentTimeMillis());
            member.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not create guild: " + exception.getMessage());
            owner.sendMessage(Component.text("Guild creation failed.", NamedTextColor.RED));
            return false;
        }
        owner.sendMessage(Component.text("Guild created: [" + tag.toUpperCase(Locale.ROOT) + "] " + name, NamedTextColor.GREEN));
        return true;
    }

    public boolean invite(Player sender, Player target) {
        GuildView guild = this.getGuildForPlayer(sender.getUniqueId());
        if (guild == null || !this.canManage(sender.getUniqueId())) {
            sender.sendMessage(Component.text("Only guild owners and officers can invite players.", NamedTextColor.RED));
            return false;
        }
        if (this.getGuildForPlayer(target.getUniqueId()) != null) {
            sender.sendMessage(Component.text(target.getName() + " is already in a guild.", NamedTextColor.YELLOW));
            return false;
        }
        if (this.getMembers(guild.guildId()).size() >= this.plugin.getConfig().getInt("mmo.guilds.max-members", 25)) {
            sender.sendMessage(Component.text("Your guild is full.", NamedTextColor.RED));
            return false;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_guild_invites (guild_id, player_uuid, player_name, invited_by, invited_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, guild.guildId());
            statement.setString(2, target.getUniqueId().toString());
            statement.setString(3, target.getName());
            statement.setString(4, sender.getUniqueId().toString());
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not invite guild member: " + exception.getMessage());
            return false;
        }
        sender.sendMessage(Component.text("Invited " + target.getName() + " to " + guild.name() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text(sender.getName() + " invited you to join [" + guild.tag() + "] " + guild.name() + ". Use /cmmo guild accept.", NamedTextColor.AQUA));
        return true;
    }

    public boolean accept(Player player) {
        GuildInvite invite = this.getInvite(player.getUniqueId());
        if (invite == null) {
            player.sendMessage(Component.text("You do not have a guild invite.", NamedTextColor.RED));
            return false;
        }
        if (this.getGuildForPlayer(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("You are already in a guild.", NamedTextColor.YELLOW));
            return false;
        }
        try (PreparedStatement member = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_guild_members (guild_id, player_uuid, player_name, rank, joined_at)
                VALUES (?, ?, ?, ?, ?)
                """);
             PreparedStatement delete = this.dataManager.getConnection().prepareStatement(
                     "DELETE FROM mmo_guild_invites WHERE player_uuid = ?")) {
            member.setString(1, invite.guildId());
            member.setString(2, player.getUniqueId().toString());
            member.setString(3, player.getName());
            member.setString(4, GuildRank.MEMBER.name());
            member.setLong(5, System.currentTimeMillis());
            member.executeUpdate();
            delete.setString(1, player.getUniqueId().toString());
            delete.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not accept guild invite: " + exception.getMessage());
            return false;
        }
        GuildView guild = this.getGuild(invite.guildId());
        player.sendMessage(Component.text("Joined " + (guild == null ? "the guild" : guild.name()) + ".", NamedTextColor.GREEN));
        return true;
    }

    public boolean leave(Player player) {
        GuildView guild = this.getGuildForPlayer(player.getUniqueId());
        if (guild == null) {
            player.sendMessage(Component.text("You are not in a guild.", NamedTextColor.YELLOW));
            return false;
        }
        GuildMember member = this.getMember(player.getUniqueId());
        if (member != null && member.rank() == GuildRank.OWNER) {
            player.sendMessage(Component.text("Owners must disband later or promote a replacement. For 1.5.0, owners cannot leave their guild.", NamedTextColor.RED));
            return false;
        }
        this.removeMember(guild.guildId(), player.getUniqueId());
        player.sendMessage(Component.text("You left " + guild.name() + ".", NamedTextColor.YELLOW));
        return true;
    }

    public boolean kick(Player sender, Player target) {
        if (!this.canManage(sender.getUniqueId())) {
            sender.sendMessage(Component.text("Only guild owners and officers can kick members.", NamedTextColor.RED));
            return false;
        }
        GuildView guild = this.getGuildForPlayer(sender.getUniqueId());
        GuildMember targetMember = this.getMember(target.getUniqueId());
        if (guild == null || targetMember == null || !guild.guildId().equals(targetMember.guildId()) || targetMember.rank() == GuildRank.OWNER) {
            sender.sendMessage(Component.text("That player cannot be kicked.", NamedTextColor.RED));
            return false;
        }
        this.removeMember(guild.guildId(), target.getUniqueId());
        sender.sendMessage(Component.text("Removed " + target.getName() + " from the guild.", NamedTextColor.YELLOW));
        target.sendMessage(Component.text("You were removed from " + guild.name() + ".", NamedTextColor.RED));
        return true;
    }

    public boolean promote(Player sender, Player target) {
        return this.setRank(sender, target, GuildRank.OFFICER, "promoted");
    }

    public boolean demote(Player sender, Player target) {
        return this.setRank(sender, target, GuildRank.MEMBER, "demoted");
    }

    public boolean setMotd(Player sender, String motd) {
        GuildView guild = this.getGuildForPlayer(sender.getUniqueId());
        if (guild == null || !this.canManage(sender.getUniqueId())) {
            sender.sendMessage(Component.text("Only guild owners and officers can set the MOTD.", NamedTextColor.RED));
            return false;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "UPDATE mmo_guilds SET motd = ? WHERE guild_id = ?")) {
            statement.setString(1, motd);
            statement.setString(2, guild.guildId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not update guild MOTD: " + exception.getMessage());
            return false;
        }
        sender.sendMessage(Component.text("Guild MOTD updated.", NamedTextColor.GREEN));
        return true;
    }

    public void sendInfo(Player player) {
        GuildView guild = this.getGuildForPlayer(player.getUniqueId());
        if (guild == null) {
            player.sendMessage(Component.text("You are not in a guild.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("[" + guild.tag() + "] " + guild.name(), NamedTextColor.GOLD));
        player.sendMessage(Component.text(guild.motd(), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Members: " + this.getMembers(guild.guildId()).size(), NamedTextColor.GRAY));
    }

    public GuildView getGuildForPlayer(UUID playerId) {
        GuildMember member = this.getMember(playerId);
        return member == null ? null : this.getGuild(member.guildId());
    }

    public GuildMember getMember(UUID playerId) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT guild_id, player_uuid, player_name, rank, joined_at
                FROM mmo_guild_members WHERE player_uuid = ?
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new GuildMember(
                            result.getString("guild_id"),
                            UUID.fromString(result.getString("player_uuid")),
                            result.getString("player_name"),
                            GuildRank.valueOf(result.getString("rank")),
                            result.getLong("joined_at")
                    );
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load guild member: " + exception.getMessage());
        }
        return null;
    }

    public GuildView getGuild(String guildId) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT guild_id, name, tag, owner_uuid, motd, created_at
                FROM mmo_guilds WHERE guild_id = ?
                """)) {
            statement.setString(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new GuildView(result.getString("guild_id"), result.getString("name"), result.getString("tag"), UUID.fromString(result.getString("owner_uuid")), result.getString("motd"), result.getLong("created_at"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load guild: " + exception.getMessage());
        }
        return null;
    }

    public List<GuildMember> getMembers(String guildId) {
        List<GuildMember> members = new ArrayList<>();
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                SELECT guild_id, player_uuid, player_name, rank, joined_at
                FROM mmo_guild_members WHERE guild_id = ?
                ORDER BY CASE rank WHEN 'OWNER' THEN 0 WHEN 'OFFICER' THEN 1 ELSE 2 END, player_name
                """)) {
            statement.setString(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(new GuildMember(result.getString("guild_id"), UUID.fromString(result.getString("player_uuid")), result.getString("player_name"), GuildRank.valueOf(result.getString("rank")), result.getLong("joined_at")));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load guild members: " + exception.getMessage());
        }
        return members;
    }

    private boolean setRank(Player sender, Player target, GuildRank rank, String verb) {
        GuildMember senderMember = this.getMember(sender.getUniqueId());
        GuildMember targetMember = this.getMember(target.getUniqueId());
        if (senderMember == null || targetMember == null || senderMember.rank() != GuildRank.OWNER || !senderMember.guildId().equals(targetMember.guildId()) || targetMember.rank() == GuildRank.OWNER) {
            sender.sendMessage(Component.text("Only guild owners can change officer ranks.", NamedTextColor.RED));
            return false;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "UPDATE mmo_guild_members SET rank = ? WHERE player_uuid = ?")) {
            statement.setString(1, rank.name());
            statement.setString(2, target.getUniqueId().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not update guild rank: " + exception.getMessage());
            return false;
        }
        sender.sendMessage(Component.text(target.getName() + " was " + verb + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text("You were " + verb + " in your guild.", NamedTextColor.GOLD));
        return true;
    }

    private boolean canManage(UUID playerId) {
        GuildMember member = this.getMember(playerId);
        return member != null && (member.rank() == GuildRank.OWNER || member.rank() == GuildRank.OFFICER);
    }

    private void removeMember(String guildId, UUID playerId) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "DELETE FROM mmo_guild_members WHERE guild_id = ? AND player_uuid = ?")) {
            statement.setString(1, guildId);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not remove guild member: " + exception.getMessage());
        }
    }

    private GuildInvite getInvite(UUID playerId) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT guild_id, invited_at FROM mmo_guild_invites WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new GuildInvite(result.getString("guild_id"), result.getLong("invited_at"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load guild invite: " + exception.getMessage());
        }
        return null;
    }

    private boolean guildExists(String name, String tag) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT 1 FROM mmo_guilds WHERE lower(name) = lower(?) OR lower(tag) = lower(?)")) {
            statement.setString(1, name);
            statement.setString(2, tag);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not check guild uniqueness: " + exception.getMessage());
            return true;
        }
    }

    private boolean validName(String name) {
        int min = this.plugin.getConfig().getInt("mmo.guilds.name-min-length", 3);
        int max = this.plugin.getConfig().getInt("mmo.guilds.name-max-length", 24);
        return name != null && name.length() >= min && name.length() <= max;
    }

    private boolean validTag(String tag) {
        int min = this.plugin.getConfig().getInt("mmo.guilds.tag-min-length", 2);
        int max = this.plugin.getConfig().getInt("mmo.guilds.tag-max-length", 5);
        return tag != null && tag.matches("[A-Za-z0-9]+") && tag.length() >= min && tag.length() <= max;
    }

    private void ensureTables() {
        try (Statement statement = this.dataManager.getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_guilds (
                        guild_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE,
                        tag TEXT NOT NULL UNIQUE,
                        owner_uuid TEXT NOT NULL,
                        motd TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_guild_members (
                        guild_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        rank TEXT NOT NULL,
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_guild_invites (
                        guild_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        invited_by TEXT,
                        invited_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid)
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Guild table setup failed: " + exception.getMessage());
        }
    }

    public enum GuildRank {
        OWNER,
        OFFICER,
        MEMBER
    }

    public record GuildView(String guildId, String name, String tag, UUID ownerId, String motd, long createdAt) {
    }

    public record GuildMember(String guildId, UUID playerId, String playerName, GuildRank rank, long joinedAt) {
    }

    private record GuildInvite(String guildId, long invitedAt) {
    }
}
