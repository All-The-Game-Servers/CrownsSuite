package com.xkstudios.crowns.moderation;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.pack.PackModelHelper;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.listener.PlayerListener;
import com.xkstudios.crowns.util.InventorySerialization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

public class ModerationManager {
    private static final String STORAGE_INV = "inventory";
    private static final String STORAGE_ENDER = "ender";
    private final CrownsPlugin plugin;
    private final Map<String, StaffRole> roles = new ConcurrentHashMap<>();
    private final Map<UUID, String> assignedRoles = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveMute> activeMutes = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveFreeze> activeFreezes = new ConcurrentHashMap<>();
    private final Map<UUID, InventorySession> inventorySessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingActions = new ConcurrentHashMap<>();
    private final Map<UUID, StaffModeState> staffModeStates = new ConcurrentHashMap<>();
    private final Map<UUID, VanishState> vanishedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PuppeteerSession> puppeteerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> puppetedTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> puppetMovementWindows = new ConcurrentHashMap<>();

    public ModerationManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.loadRoles();
        this.loadAssignedRoles();
        this.loadActiveMutes();
        this.loadActiveFreezes();
        this.loadPersistedVanishStates();
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("moderation.enabled", true);
    }

    public boolean hasCapability(Player player, StaffCapability capability) {
        if (player == null) {
            return false;
        }
        if (player.isOp() || player.hasPermission("crowns.admin") || player.hasPermission("crowns.mod") || player.hasPermission(capability.permission())) {
            return true;
        }
        StaffRole role = this.getAssignedRole(player.getUniqueId());
        return role != null && role.has(capability);
    }

    public StaffRole getAssignedRole(UUID playerId) {
        String key = this.assignedRoles.get(playerId);
        return key == null ? null : this.roles.get(key);
    }

    public String getRoleBadge(UUID playerId) {
        if (!this.plugin.getConfig().getBoolean("moderation.role-chat-badges", true)) {
            return null;
        }
        StaffRole role = this.getAssignedRole(playerId);
        return role == null ? null : role.displayName();
    }

    public TextColor getRoleColor(UUID playerId) {
        StaffRole role = this.getAssignedRole(playerId);
        if (role == null || role.color() == null) {
            return null;
        }
        TextColor color = TextColor.fromHexString(role.color());
        return color == null ? NamedTextColor.AQUA : color;
    }

    public Set<String> getRoleKeys() {
        return this.roles.keySet();
    }

    public List<ModerationReport> getOpenReports() {
        List<ModerationReport> reports = new ArrayList<>();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT * FROM moderation_reports WHERE status IN ('open','claimed') ORDER BY created_at DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reports.add(this.readReport(resultSet));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load reports failed: " + exception.getMessage());
        }
        return reports;
    }

    public List<ModerationAction> getHistory(UUID targetId, int limit) {
        List<ModerationAction> actions = new ArrayList<>();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT id, actor_name, target_name, action_type, reason, created_at, expires_at, active FROM moderation_actions WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?")) {
            statement.setString(1, targetId.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actions.add(new ModerationAction(
                            resultSet.getLong("id"),
                            resultSet.getString("actor_name"),
                            resultSet.getString("target_name"),
                            resultSet.getString("action_type"),
                            resultSet.getString("reason"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("expires_at"),
                            resultSet.getInt("active") == 1
                    ));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load history failed: " + exception.getMessage());
        }
        return actions;
    }

    public List<ModerationAction> getRecentActions(int limit) {
        List<ModerationAction> actions = new ArrayList<>();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT id, actor_name, target_name, action_type, reason, created_at, expires_at, active FROM moderation_actions ORDER BY created_at DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actions.add(new ModerationAction(
                            resultSet.getLong("id"),
                            resultSet.getString("actor_name"),
                            resultSet.getString("target_name"),
                            resultSet.getString("action_type"),
                            resultSet.getString("reason"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("expires_at"),
                            resultSet.getInt("active") == 1
                    ));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load recent actions failed: " + exception.getMessage());
        }
        return actions;
    }

    public int getActiveMuteCount() {
        return this.activeMutes.size();
    }

    public int getFrozenCount() {
        return this.activeFreezes.size();
    }

    public int getVanishedCount() {
        return this.vanishedPlayers.size();
    }

    public int getPuppeteerCount() {
        return this.puppeteerSessions.size();
    }

    public boolean assignRole(Player actor, OfflinePlayer target, String roleKey) {
        StaffRole role = this.roles.get(roleKey.toLowerCase(Locale.ROOT));
        if (role == null || target.getUniqueId() == null) {
            return false;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO staff_roles (player_uuid, player_name, role_key, assigned_by_uuid, assigned_by_name, assigned_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getName());
            statement.setString(3, role.key());
            statement.setString(4, actor.getUniqueId().toString());
            statement.setString(5, actor.getName());
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Role assign failed: " + exception.getMessage());
            return false;
        }
        this.assignedRoles.put(target.getUniqueId(), role.key());
        this.logAction(actor, target.getUniqueId(), target.getName(), "role_assign", "Assigned role " + role.displayName(), 0L, true, "command");
        Player online = target.getPlayer();
        if (online != null) {
            PlayerListener.refreshTag(this.plugin, online);
            online.sendMessage(Component.text("Your staff role is now " + role.displayName() + ".", NamedTextColor.GOLD));
        }
        return true;
    }

    public boolean clearRole(Player actor, OfflinePlayer target) {
        if (target.getUniqueId() == null) {
            return false;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM staff_roles WHERE player_uuid = ?")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Role clear failed: " + exception.getMessage());
            return false;
        }
        this.assignedRoles.remove(target.getUniqueId());
        this.logAction(actor, target.getUniqueId(), target.getName(), "role_clear", "Cleared staff role", 0L, false, "command");
        Player online = target.getPlayer();
        if (online != null) {
            PlayerListener.refreshTag(this.plugin, online);
            online.sendMessage(Component.text("Your staff role has been cleared.", NamedTextColor.YELLOW));
        }
        return true;
    }

    public boolean createReport(Player reporter, OfflinePlayer target, String reason) {
        if (!this.plugin.getConfig().getBoolean("moderation.reports.enabled", true) || target.getUniqueId() == null) {
            return false;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO moderation_reports (reporter_uuid, reporter_name, target_uuid, target_name, reason, status, claimed_by_uuid, claimed_by_name, created_at, resolved_at, resolution_note) VALUES (?, ?, ?, ?, ?, 'open', NULL, NULL, ?, 0, NULL)")) {
            statement.setString(1, reporter.getUniqueId().toString());
            statement.setString(2, reporter.getName());
            statement.setString(3, target.getUniqueId().toString());
            statement.setString(4, target.getName());
            statement.setString(5, reason);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Report create failed: " + exception.getMessage());
            return false;
        }
        this.pushStaffNotice("New player report", reporter.getName() + " reported " + target.getName() + ": " + reason);
        return true;
    }

    public boolean warn(Player actor, OfflinePlayer target, String reason, String source) {
        return this.recordSimpleAction(actor, target, "warn", reason, source, true);
    }

    public boolean note(Player actor, OfflinePlayer target, String note, String source) {
        return this.recordSimpleAction(actor, target, "note", note, source, false);
    }

    public boolean kick(Player actor, Player target, String reason, String source) {
        this.logAction(actor, target.getUniqueId(), target.getName(), "kick", reason, 0L, false, source);
        target.kick(Component.text("Kicked by staff: " + reason, NamedTextColor.RED));
        return true;
    }

    public boolean mute(Player actor, OfflinePlayer target, long durationMillis, String reason, String source) {
        if (target.getUniqueId() == null) {
            return false;
        }
        long expiresAt = durationMillis <= 0L ? 0L : System.currentTimeMillis() + durationMillis;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO moderation_mutes (target_uuid, target_name, reason, actor_uuid, actor_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getName());
            statement.setString(3, reason);
            statement.setString(4, actor.getUniqueId().toString());
            statement.setString(5, actor.getName());
            statement.setLong(6, System.currentTimeMillis());
            statement.setLong(7, expiresAt);
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Mute failed: " + exception.getMessage());
            return false;
        }
        this.activeMutes.put(target.getUniqueId(), new ActiveMute(target.getUniqueId(), target.getName(), reason, expiresAt));
        this.logAction(actor, target.getUniqueId(), target.getName(), "mute", reason, expiresAt, true, source);
        this.notifyTarget(target, "You have been muted.", reason);
        return true;
    }

    public boolean unmute(Player actor, OfflinePlayer target, String source) {
        if (target.getUniqueId() == null) {
            return false;
        }
        this.activeMutes.remove(target.getUniqueId());
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM moderation_mutes WHERE target_uuid = ?")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Unmute failed: " + exception.getMessage());
            return false;
        }
        this.resolveActiveActions(target.getUniqueId(), "mute");
        this.logAction(actor, target.getUniqueId(), target.getName(), "unmute", "Mute removed", 0L, false, source);
        this.notifyTarget(target, "You have been unmuted.", null);
        return true;
    }

    public boolean freeze(Player actor, OfflinePlayer target, String reason, String source) {
        if (target.getUniqueId() == null) {
            return false;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO moderation_freezes (target_uuid, target_name, reason, actor_uuid, actor_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 0)")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getName());
            statement.setString(3, reason);
            statement.setString(4, actor.getUniqueId().toString());
            statement.setString(5, actor.getName());
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Freeze failed: " + exception.getMessage());
            return false;
        }
        this.activeFreezes.put(target.getUniqueId(), new ActiveFreeze(target.getUniqueId(), target.getName(), reason));
        this.logAction(actor, target.getUniqueId(), target.getName(), "freeze", reason, 0L, true, source);
        this.notifyTarget(target, "You have been frozen by staff.", reason);
        return true;
    }

    public boolean unfreeze(Player actor, OfflinePlayer target, String source) {
        if (target.getUniqueId() == null) {
            return false;
        }
        this.activeFreezes.remove(target.getUniqueId());
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM moderation_freezes WHERE target_uuid = ?")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Unfreeze failed: " + exception.getMessage());
            return false;
        }
        this.resolveActiveActions(target.getUniqueId(), "freeze");
        this.logAction(actor, target.getUniqueId(), target.getName(), "unfreeze", "Freeze removed", 0L, false, source);
        this.notifyTarget(target, "You have been unfrozen.", null);
        return true;
    }

    public boolean ban(Player actor, OfflinePlayer target, long durationMillis, String reason, String source) {
        if (target.getName() == null) {
            return false;
        }
        Instant expires = durationMillis <= 0L ? null : Instant.now().plusMillis(durationMillis);
        try {
            Bukkit.getBanList(BanList.Type.NAME).addBan(target.getName(), reason, expires == null ? null : java.util.Date.from(expires), actor.getName());
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Ban failed: " + exception.getMessage());
            return false;
        }
        this.logAction(actor, target.getUniqueId(), target.getName(), "ban", reason, expires == null ? 0L : expires.toEpochMilli(), true, source);
        Player online = target.getPlayer();
        if (online != null) {
            online.kick(Component.text("Banned by staff: " + reason, NamedTextColor.RED));
        }
        return true;
    }

    public boolean unban(Player actor, String name, String source) {
        try {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Unban failed: " + exception.getMessage());
            return false;
        }
        UUID uuid = this.plugin.getDataManager().findUuidByName(name);
        this.resolveActiveActions(uuid, "ban");
        this.logAction(actor, uuid, name, "unban", "Ban removed", 0L, false, source);
        return true;
    }

    public boolean isMuted(UUID playerId) {
        ActiveMute mute = this.activeMutes.get(playerId);
        if (mute == null) {
            return false;
        }
        if (mute.expiresAt() > 0L && mute.expiresAt() <= System.currentTimeMillis()) {
            this.expireMute(playerId);
            return false;
        }
        return true;
    }

    public boolean isFrozen(UUID playerId) {
        return this.activeFreezes.containsKey(playerId) || this.puppetedTargets.containsKey(playerId);
    }

    public boolean isMovementLocked(UUID playerId) {
        if (this.activeFreezes.containsKey(playerId)) {
            return true;
        }
        if (!this.puppetedTargets.containsKey(playerId)) {
            return false;
        }
        return !this.isPuppetMovementAllowed(playerId);
    }

    public boolean isControlLocked(UUID playerId) {
        return this.activeFreezes.containsKey(playerId) || this.puppetedTargets.containsKey(playerId);
    }

    public boolean isPuppeteering(UUID controllerId) {
        return this.puppeteerSessions.containsKey(controllerId);
    }

    public String mutedReason(UUID playerId) {
        ActiveMute mute = this.activeMutes.get(playerId);
        return mute == null ? null : mute.reason();
    }

    public String frozenReason(UUID playerId) {
        ActiveFreeze freeze = this.activeFreezes.get(playerId);
        return freeze == null ? null : freeze.reason();
    }

    public boolean toggleStaffMode(Player player) {
        if (this.staffModeStates.containsKey(player.getUniqueId())) {
            this.disableStaffMode(player);
            return false;
        }
        this.enableStaffMode(player);
        return true;
    }

    public boolean isInStaffMode(UUID playerId) {
        return this.staffModeStates.containsKey(playerId);
    }

    public boolean isVanished(UUID playerId) {
        return this.vanishedPlayers.containsKey(playerId);
    }

    public boolean toggleVanish(Player player) {
        return this.setVanished(player, !this.isVanished(player.getUniqueId()), "command");
    }

    public boolean setVanished(Player player, boolean vanished, String source) {
        if (vanished) {
            if (this.isVanished(player.getUniqueId())) {
                return true;
            }
            return this.enableVanish(player, source);
        }
        if (!this.isVanished(player.getUniqueId())) {
            return false;
        }
        this.disableVanish(player, source);
        return false;
    }

    public boolean startPuppeteer(Player controller, Player target) {
        if (!this.plugin.getConfig().getBoolean("moderation.puppeteer.enabled", true) || controller.equals(target)) {
            return false;
        }
        this.stopPuppeteer(controller.getUniqueId(), "replaced");
        if (this.puppetedTargets.containsKey(target.getUniqueId())) {
            return false;
        }
        PuppeteerSession session = new PuppeteerSession(
                controller.getUniqueId(),
                controller.getName(),
                target.getUniqueId(),
                target.getName(),
                System.currentTimeMillis(),
                controller.getGameMode(),
                controller.getLocation().clone(),
                controller.getAllowFlight(),
                controller.isFlying(),
                controller.isInvulnerable(),
                controller.isInvisible(),
                controller.getSpectatorTarget()
        );
        this.puppeteerSessions.put(controller.getUniqueId(), session);
        this.puppetedTargets.put(target.getUniqueId(), controller.getUniqueId());
        controller.setInvulnerable(true);
        controller.setInvisible(true);
        controller.setGameMode(GameMode.SPECTATOR);
        controller.setSpectatorTarget(target);
        controller.teleport(target.getLocation());
        this.allowPuppetMovement(target.getUniqueId());
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO puppeteer_sessions (controller_uuid, controller_name, target_uuid, target_name, started_at, ended_at, source) VALUES (?, ?, ?, ?, ?, 0, ?)")) {
            statement.setString(1, controller.getUniqueId().toString());
            statement.setString(2, controller.getName());
            statement.setString(3, target.getUniqueId().toString());
            statement.setString(4, target.getName());
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, "command");
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
        this.logAction(controller, target.getUniqueId(), target.getName(), "puppet_start", "Puppeteer started", 0L, true, "command");
        return true;
    }

    public boolean stopPuppeteer(UUID controllerId, String reason) {
        PuppeteerSession session = this.puppeteerSessions.remove(controllerId);
        if (session == null) {
            return false;
        }
        this.puppetedTargets.remove(session.targetUuid());
        this.puppetMovementWindows.remove(session.targetUuid());
        Player controller = Bukkit.getPlayer(controllerId);
        if (controller != null) {
            controller.setSpectatorTarget(null);
            controller.setGameMode(session.originalGameMode());
            controller.setAllowFlight(session.originalAllowFlight());
            controller.setFlying(session.originalFlying());
            controller.setInvulnerable(session.originalInvulnerable());
            controller.setInvisible(session.originalInvisible());
            if (session.originalLocation() != null) {
                controller.teleport(session.originalLocation());
            }
            if (session.originalSpectatorTarget() != null && controller.getGameMode() == GameMode.SPECTATOR) {
                controller.setSpectatorTarget(session.originalSpectatorTarget());
            }
        }
        this.resolveActiveActions(session.targetUuid(), "puppet_start");
        this.logAction(controller, session.targetUuid(), session.targetName(), "puppet_stop", reason, 0L, false, "system");
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE puppeteer_sessions SET ended_at = ? WHERE controller_uuid = ? AND target_uuid = ? AND ended_at = 0")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, session.controllerUuid().toString());
            statement.setString(3, session.targetUuid().toString());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
        return true;
    }

    public void handlePuppetInput(Player controller, org.bukkit.Input input) {
        PuppeteerSession session = this.puppeteerSessions.get(controller.getUniqueId());
        if (session == null) {
            return;
        }
        Player target = Bukkit.getPlayer(session.targetUuid());
        if (target == null) {
            this.stopPuppeteer(controller.getUniqueId(), "target disconnected");
            return;
        }
        Vector direction = controller.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 0);
        }
        Vector sideways = new Vector(-direction.getZ(), 0, direction.getX());
        Vector velocity = new Vector();
        if (input.isForward()) {
            velocity.add(direction);
        }
        if (input.isBackward()) {
            velocity.subtract(direction);
        }
        if (input.isLeft()) {
            velocity.subtract(sideways);
        }
        if (input.isRight()) {
            velocity.add(sideways);
        }
        if (velocity.lengthSquared() > 0) {
            velocity.normalize().multiply(input.isSprint() ? 0.35 : 0.24);
        }
        velocity.setY(input.isJump() ? 0.42 : target.getVelocity().getY());
        this.allowPuppetMovement(target.getUniqueId());
        target.teleport(target.getLocation().setDirection(controller.getLocation().getDirection()));
        target.setVelocity(velocity);
        controller.setSpectatorTarget(target);
    }

    public void handlePuppetLook(Player controller) {
        PuppeteerSession session = this.puppeteerSessions.get(controller.getUniqueId());
        if (session == null) {
            return;
        }
        Player target = Bukkit.getPlayer(session.targetUuid());
        if (target == null || !target.isOnline() || target.isDead()) {
            this.stopPuppeteer(controller.getUniqueId(), "target unavailable");
            return;
        }
        this.allowPuppetMovement(target.getUniqueId());
        target.teleport(target.getLocation().setDirection(controller.getLocation().getDirection()));
        controller.setSpectatorTarget(target);
    }

    public void handlePuppetDeath(UUID playerId) {
        if (this.stopPuppeteer(playerId, "controller died")) {
            return;
        }
        UUID controller = this.puppetedTargets.get(playerId);
        if (controller != null) {
            this.stopPuppeteer(controller, "target died");
        }
    }

    public void handleJoin(Player player) {
        this.applyStoredState(player);
        if (this.isVanished(player.getUniqueId())) {
            player.setCollidable(false);
        }
        this.syncVisibilityFor(player);
        this.syncViewerForAll(player);
        if (this.isMuted(player.getUniqueId())) {
            player.sendMessage(Component.text("You are muted: " + this.mutedReason(player.getUniqueId()), NamedTextColor.RED));
        }
        if (this.isFrozen(player.getUniqueId())) {
            player.sendMessage(Component.text("You are frozen. Stay where you are.", NamedTextColor.RED));
        }
    }

    public void handleQuit(Player player) {
        this.capturePlayerState(player);
        this.stopPuppeteer(player.getUniqueId(), "controller disconnected");
        UUID controller = this.puppetedTargets.get(player.getUniqueId());
        if (controller != null) {
            this.stopPuppeteer(controller, "target disconnected");
        }
    }

    public void capturePlayerState(Player player) {
        this.savePlayerStorage(player.getUniqueId(), player.getName(),
                player.getInventory().getStorageContents(),
                player.getInventory().getArmorContents(),
                new ItemStack[]{player.getInventory().getItemInOffHand()},
                player.getEnderChest().getContents());
    }

    public boolean openInventoryView(Player viewer, OfflinePlayer target) {
        if (target.getUniqueId() == null) {
            return false;
        }
        PlayerStorageState state = this.loadState(target);
        if (state == null) {
            return false;
        }
        Inventory inventory = Bukkit.createInventory(null, 54, Component.text("Invsee: " + safeName(target), NamedTextColor.GOLD));
        ItemStack[] main = state.inventory();
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, i < main.length ? cloneItem(main[i]) : null);
        }
        ItemStack[] armor = state.armor();
        inventory.setItem(36, armor.length > 3 ? cloneItem(armor[3]) : null);
        inventory.setItem(37, armor.length > 2 ? cloneItem(armor[2]) : null);
        inventory.setItem(38, armor.length > 1 ? cloneItem(armor[1]) : null);
        inventory.setItem(39, armor.length > 0 ? cloneItem(armor[0]) : null);
        inventory.setItem(40, state.offhand().length > 0 ? cloneItem(state.offhand()[0]) : null);
        inventory.setItem(45, this.info(Material.IRON_HELMET, "Armor", "36-39"));
        inventory.setItem(46, this.info(Material.SHIELD, "Offhand", "40"));
        inventory.setItem(49, this.info(Material.PLAYER_HEAD, safeName(target), state.online() ? "Online" : "Offline snapshot"));
        inventory.setItem(53, this.button(Material.ENDER_CHEST, "Open Ender Chest", NamedTextColor.AQUA, List.of(), "ce:mod:openender:" + target.getUniqueId()));
        this.inventorySessions.put(viewer.getUniqueId(), new InventorySession(viewer.getUniqueId(), target.getUniqueId(), safeName(target), STORAGE_INV, inventory, state.online(), state.ender()));
        viewer.openInventory(inventory);
        return true;
    }

    public boolean openEnderChestView(Player viewer, OfflinePlayer target) {
        if (target.getUniqueId() == null) {
            return false;
        }
        PlayerStorageState state = this.loadState(target);
        if (state == null) {
            return false;
        }
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text("Ender: " + safeName(target), NamedTextColor.GOLD));
        inventory.setContents(copyItems(state.ender(), 27));
        this.inventorySessions.put(viewer.getUniqueId(), new InventorySession(viewer.getUniqueId(), target.getUniqueId(), safeName(target), STORAGE_ENDER, inventory, state.online(), state.ender()));
        viewer.openInventory(inventory);
        return true;
    }

    public boolean handleInventoryEditorClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        InventorySession session = this.inventorySessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || !session.inventory().equals(event.getView().getTopInventory())) {
            return false;
        }
        if (session.type().equals(STORAGE_INV) && event.getRawSlot() >= 45 && event.getRawSlot() < 54 && event.getRawSlot() != 53) {
            event.setCancelled(true);
        }
        return true;
    }

    public boolean handleInventoryEditorDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        InventorySession session = this.inventorySessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || !session.inventory().equals(event.getView().getTopInventory())) {
            return false;
        }
        if (session.type().equals(STORAGE_INV)) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 45 && rawSlot < 54 && rawSlot != 53) {
                    event.setCancelled(true);
                    break;
                }
            }
        }
        return true;
    }

    public void handleInventoryEditorClose(Player viewer) {
        InventorySession session = this.inventorySessions.remove(viewer.getUniqueId());
        if (session == null) {
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid());
        if (session.type().equals(STORAGE_ENDER)) {
            this.snapshotState(target, viewer, "Saved ender chest editor");
            PlayerStorageState existing = this.loadState(target);
            if (existing != null) {
                this.savePlayerStorage(session.targetUuid(), session.targetName(),
                        existing.inventory(),
                        existing.armor(),
                        existing.offhand(),
                        session.inventory().getContents());
            }
            if (existing != null && existing.online()) {
                Player online = target.getPlayer();
                if (online != null) {
                    online.getEnderChest().setContents(copyItems(session.inventory().getContents(), 27));
                }
            }
            return;
        }
        ItemStack[] main = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            main[i] = cloneItem(session.inventory().getItem(i));
        }
        ItemStack[] armor = new ItemStack[]{
                cloneItem(session.inventory().getItem(39)),
                cloneItem(session.inventory().getItem(38)),
                cloneItem(session.inventory().getItem(37)),
                cloneItem(session.inventory().getItem(36))
        };
        ItemStack[] offhand = new ItemStack[]{cloneItem(session.inventory().getItem(40))};
        this.snapshotState(target, viewer, "Saved inventory editor");
        this.savePlayerStorage(session.targetUuid(), session.targetName(), main, armor, offhand, session.cachedEnder());
        Player online = target.getPlayer();
        if (online != null) {
            online.getInventory().setStorageContents(copyItems(main, 36));
            online.getInventory().setArmorContents(copyItems(armor, 4));
            online.getInventory().setItemInOffHand(offhand.length > 0 ? cloneItem(offhand[0]) : null);
            online.updateInventory();
        }
    }

    public List<String> getDefaultMuteDurations() {
        return this.plugin.getConfig().getStringList("moderation.mute.default-durations");
    }

    public List<String> getDefaultBanDurations() {
        return this.plugin.getConfig().getStringList("moderation.ban.default-durations");
    }

    public long parseDuration(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("permanent") || raw.equalsIgnoreCase("perm")) {
            return 0L;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).trim();
        long multiplier = 1000L;
        if (normalized.endsWith("m")) {
            multiplier = 60000L;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("h")) {
            multiplier = 3600000L;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("d")) {
            multiplier = 86400000L;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("w")) {
            multiplier = 604800000L;
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Long.parseLong(normalized) * multiplier;
    }

    public void setAwaitingAction(UUID playerId, String action) {
        this.awaitingActions.put(playerId, action);
    }

    public String getAwaitingAction(UUID playerId) {
        return this.awaitingActions.get(playerId);
    }

    public void clearAwaitingAction(UUID playerId) {
        this.awaitingActions.remove(playerId);
    }

    public void handleChatPrompt(Player player, String action, String message) {
        String trimmed = message.trim();
        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
            return;
        }
        try {
            if (action.startsWith("warn:")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(action.substring(5)));
                this.warn(player, target, trimmed, "gui");
                player.sendMessage(Component.text("Warned " + safeName(target) + ".", NamedTextColor.GREEN));
            } else if (action.startsWith("note:")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(action.substring(5)));
                this.note(player, target, trimmed, "gui");
                player.sendMessage(Component.text("Note added for " + safeName(target) + ".", NamedTextColor.GREEN));
            } else if (action.startsWith("mute:")) {
                String[] parts = action.split(":");
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(parts[1]));
                long duration = this.parseDuration(parts[2]);
                this.mute(player, target, duration, trimmed, "gui");
                player.sendMessage(Component.text("Muted " + safeName(target) + ".", NamedTextColor.GREEN));
            } else if (action.startsWith("ban:")) {
                String[] parts = action.split(":");
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(parts[1]));
                long duration = this.parseDuration(parts[2]);
                this.ban(player, target, duration, trimmed, "gui");
                player.sendMessage(Component.text("Banned " + safeName(target) + ".", NamedTextColor.GREEN));
            }
        } catch (Exception exception) {
            player.sendMessage(Component.text("That moderation action could not be completed.", NamedTextColor.RED));
        }
    }

    public void openStaffHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-hub", 54, Component.text("Staff Tools", NamedTextColor.GOLD));
        inventory.setItem(10, this.button(Material.IRON_SWORD, "Moderation", NamedTextColor.RED, List.of(
                Component.text("Jump into player profiles and actions.", NamedTextColor.GRAY)
        ), "ce:mod:directory", "lowlight/admin/moderation"));
        inventory.setItem(12, this.button(Material.COMPASS, "Reports", NamedTextColor.YELLOW, List.of(
                Component.text("Open reports: " + this.getOpenReports().size(), NamedTextColor.GRAY)
        ), "ce:mod:reports", "lowlight/admin/reports"));
        inventory.setItem(14, this.button(Material.CHEST, "Player Inspection", NamedTextColor.AQUA, List.of(
                Component.text("Browse online players and inspect inventories.", NamedTextColor.GRAY)
        ), "ce:mod:directory", "lowlight/admin/player_inspection"));
        inventory.setItem(16, this.button(Material.FEATHER, "Staff Mode", NamedTextColor.GREEN, List.of(
                Component.text(this.isInStaffMode(player.getUniqueId()) ? "Disable the staff workflow bundle." : "Enable the staff workflow bundle.", NamedTextColor.GRAY)
        ), "ce:mod:staffmode", "lowlight/admin/staff_mode"));
        inventory.setItem(20, this.button(Material.ENDER_EYE, "True Vanish", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text(this.isVanished(player.getUniqueId()) ? "You are hidden from normal players." : "Disappear without leaving staff mode.", NamedTextColor.GRAY)
        ), "ce:mod:vanish", "lowlight/admin/true_vanish"));
        inventory.setItem(22, this.button(Material.BOOK, "Analytics", NamedTextColor.AQUA, List.of(
                Component.text("Economy analytics and playtime shortcuts.", NamedTextColor.GRAY)
        ), "ce:mod:analyticshub", "lowlight/admin/analytics"));
        inventory.setItem(24, this.button(Material.CLOCK, "Playtime", NamedTextColor.WHITE, List.of(
                Component.text("Jump to playtime tools and leaderboards.", NamedTextColor.GRAY)
        ), "ce:mod:playtimehub", "lowlight/admin/playtime"));
        inventory.setItem(28, this.button(Material.HOPPER, "Entity Tools", NamedTextColor.YELLOW, List.of(
                Component.text("Clear items, clear mobs, and inspect counts.", NamedTextColor.GRAY)
        ), "ce:mod:entityhub", "lowlight/admin/entity_tools"));
        inventory.setItem(30, this.button(Material.NAME_TAG, "Roles", NamedTextColor.AQUA, List.of(
                Component.text("Your role: " + this.describeRole(player.getUniqueId()), NamedTextColor.GRAY)
        ), "ce:mod:roleself", "lowlight/admin/roles"));
        inventory.setItem(32, this.info(Material.PAPER, "Operations Snapshot",
                "Reports: " + this.getOpenReports().size() + " | Mutes: " + this.getActiveMuteCount() + " | Freezes: " + this.getFrozenCount(),
                "lowlight/admin/operations"));
        if (this.isPuppeteering(player.getUniqueId())) {
            PuppeteerSession session = this.puppeteerSessions.get(player.getUniqueId());
            inventory.setItem(34, this.button(Material.BARRIER, "Stop Puppet", NamedTextColor.RED, List.of(
                    Component.text("Current target: " + (session == null ? "Unknown" : session.targetName()), NamedTextColor.GRAY)
            ), "ce:mod:puppetstop", "lowlight/admin/puppeteering"));
        } else {
            inventory.setItem(34, this.info(Material.FEATHER, "Puppeteering", "No active puppet session.", "lowlight/admin/puppeteering"));
        }
        inventory.setItem(37, this.info(Material.COMPASS, "Open Reports", this.getOpenReports().isEmpty()
                ? "No open reports."
                : this.getOpenReports().get(0).targetName() + " needs review.", "lowlight/admin/reports"));
        inventory.setItem(39, this.info(Material.ENDER_EYE, "Vanished Staff",
                this.getVanishedCount() == 0 ? "Nobody vanished right now." : this.getVanishedCount() + " staff hidden right now.",
                "lowlight/admin/true_vanish"));
        inventory.setItem(41, this.info(Material.ICE, "Frozen Players",
                this.getFrozenCount() == 0 ? "Nobody frozen right now." : this.getFrozenCount() + " player(s) currently locked.",
                "lowlight/admin/moderation"));
        List<ModerationAction> recentActions = this.getRecentActions(1);
        inventory.setItem(43, this.info(Material.WRITABLE_BOOK, "Recent Action",
                recentActions.isEmpty()
                        ? "No recent moderation action logged."
                        : recentActions.get(0).actorName() + " -> " + recentActions.get(0).targetName() + " (" + recentActions.get(0).actionType() + ")",
                "lowlight/admin/analytics"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:suite", "lowlight/suite/nav_back"));
        player.openInventory(inventory);
    }

    public void openPlayerDirectory(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-directory", 54, Component.text("Player Inspection", NamedTextColor.GOLD));
        int slot = 10;
        for (Player online : Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName)).toList()) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(Material.PLAYER_HEAD, online.getName(), NamedTextColor.AQUA, List.of(
                    Component.text("Role: " + this.describeRole(online.getUniqueId()), NamedTextColor.GRAY),
                    Component.text("Open moderation profile.", NamedTextColor.GRAY)
            ), "ce:mod:profile:" + online.getUniqueId()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (slot == 10) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Online Players", "Nobody is online right now."));
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openAnalyticsHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-analytics", 54, Component.text("Analytics Tools", NamedTextColor.GOLD));
        inventory.setItem(20, this.button(Material.GOLD_INGOT, "Economy Today", NamedTextColor.GOLD, List.of(
                Component.text("Run /ca analytics economy today", NamedTextColor.GRAY)
        ), "ce:mod:analyticstoday"));
        inventory.setItem(22, this.button(Material.CLOCK, "Playtime Top", NamedTextColor.AQUA, List.of(
                Component.text("Run /ca playtime top today", NamedTextColor.GRAY)
        ), "ce:mod:playtimetop"));
        inventory.setItem(24, this.info(Material.WRITABLE_BOOK, "Deep Dive", "Use /ca analytics and /ca playtime for deeper detail."));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openEntityToolsHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-entity-tools", 54, Component.text("Entity Tools", NamedTextColor.GOLD));
        inventory.setItem(20, this.button(Material.HOPPER, "Entity Breakdown", NamedTextColor.YELLOW, List.of(
                Component.text("Send the current world breakdown to chat.", NamedTextColor.GRAY)
        ), "ce:mod:entitiesnow"));
        inventory.setItem(22, this.button(Material.ROTTEN_FLESH, "Clear Hostile Mobs", NamedTextColor.RED, List.of(
                Component.text("Runs a 64-block hostile mob clear around you.", NamedTextColor.GRAY)
        ), "ce:mod:clearmobsnow"));
        inventory.setItem(24, this.button(Material.COBWEB, "Clear Dropped Items", NamedTextColor.AQUA, List.of(
                Component.text("Runs a 64-block item sweep around you.", NamedTextColor.GRAY)
        ), "ce:mod:clearitemsnow"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openSelfRoleSummary(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-self-role", 54, Component.text("My Staff Role", NamedTextColor.GOLD));
        StaffRole role = this.getAssignedRole(player.getUniqueId());
        inventory.setItem(22, this.info(Material.NAME_TAG, "Current Role", this.describeRole(player.getUniqueId())));
        inventory.setItem(31, this.info(Material.BOOK, "Capabilities",
                role == null ? "No assigned capabilities." : String.join(", ", role.capabilities().stream().map(StaffCapability::key).sorted().toList())));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openReportsMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mod-reports", 54, Component.text("Moderation Reports", NamedTextColor.GOLD));
        int slot = 10;
        for (ModerationReport report : this.getOpenReports()) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(Material.PAPER, report.targetName(), NamedTextColor.YELLOW, List.of(
                    Component.text("Reporter: " + report.reporterName(), NamedTextColor.GRAY),
                    Component.text("Status: " + report.status(), NamedTextColor.GRAY),
                    Component.text(report.reason(), NamedTextColor.WHITE)
            ), "ce:mod:profile:" + report.targetUuid()));
            inventory.setItem(slot + 9, this.button(Material.LIME_DYE, "Claim", NamedTextColor.GREEN, List.of(), "ce:mod:reportclaim:" + report.id()));
            inventory.setItem(slot + 18, this.button(Material.BARRIER, "Dismiss", NamedTextColor.RED, List.of(), "ce:mod:reportdismiss:" + report.id()));
            slot++;
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openPlayerProfile(Player player, OfflinePlayer target) {
        Inventory inventory = CrownsMenuHolder.create("mod-profile", 54, Component.text("Profile: " + safeName(target), NamedTextColor.GOLD));
        inventory.setItem(10, this.info(Material.PLAYER_HEAD, safeName(target), this.describeRole(target.getUniqueId())));
        List<ModerationAction> history = target.getUniqueId() == null ? List.of() : this.getHistory(target.getUniqueId(), 5);
        long warnCount = history.stream().filter(action -> "warn".equalsIgnoreCase(action.actionType())).count();
        long muteCount = history.stream().filter(action -> action.actionType().toLowerCase(Locale.ROOT).contains("mute")).count();
        long banCount = history.stream().filter(action -> action.actionType().toLowerCase(Locale.ROOT).contains("ban")).count();
        long noteCount = history.stream().filter(action -> "note".equalsIgnoreCase(action.actionType())).count();
        inventory.setItem(12, this.info(Material.BOOK, "Case File", "Warns: " + warnCount + " | Notes: " + noteCount, "lowlight/admin/case_file"));
        inventory.setItem(13, this.info(Material.CLOCK, "Punishments", "Mutes: " + muteCount + " | Bans: " + banCount, "lowlight/admin/analytics"));
        inventory.setItem(14, this.info(Material.PAPER, "Recent History", history.isEmpty() ? "No moderation history yet." : history.get(0).actionType() + ": " + (history.get(0).reason() == null ? "No reason" : history.get(0).reason()), "lowlight/admin/reports"));
        int slot = 19;
        for (ModerationAction action : history) {
            if (slot > 23) {
                break;
            }
            inventory.setItem(slot++, this.info(Material.PAPER, action.actionType(), action.reason() == null ? "No reason" : action.reason()));
        }
        inventory.setItem(28, this.button(Material.CHEST, "Invsee", NamedTextColor.AQUA, List.of(), "ce:mod:openinv:" + target.getUniqueId()));
        inventory.setItem(29, this.button(Material.ENDER_CHEST, "Ender Chest", NamedTextColor.AQUA, List.of(), "ce:mod:openender:" + target.getUniqueId()));
        inventory.setItem(30, this.button(Material.BOOK, "Warn", NamedTextColor.YELLOW, List.of(
                Component.text("Prompt for a warning reason.", NamedTextColor.GRAY)
        ), "ce:mod:promptwarn:" + target.getUniqueId()));
        inventory.setItem(31, this.button(Material.WRITABLE_BOOK, "Note", NamedTextColor.YELLOW, List.of(), "ce:mod:promptnote:" + target.getUniqueId()));
        inventory.setItem(32, this.button(Material.IRON_BARS, "Freeze", NamedTextColor.RED, List.of(), "ce:mod:freeze:" + target.getUniqueId()));
        inventory.setItem(33, this.button(Material.REDSTONE_BLOCK, "Kick", NamedTextColor.RED, List.of(), "ce:mod:kick:" + target.getUniqueId()));
        inventory.setItem(37, this.button(Material.CLOCK, "Mute 1h", NamedTextColor.GOLD, List.of(), "ce:mod:promptmute:" + target.getUniqueId() + ":1h"));
        inventory.setItem(38, this.button(Material.BARRIER, "Ban 1d", NamedTextColor.RED, List.of(), "ce:mod:promptban:" + target.getUniqueId() + ":1d"));
        boolean puppeted = target.getUniqueId() != null && this.puppetedTargets.containsKey(target.getUniqueId());
        inventory.setItem(39, this.button(puppeted ? Material.BARRIER : Material.FEATHER, puppeted ? "Stop Puppet" : "Puppet",
                puppeted ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE, List.of(), puppeted ? "ce:mod:puppetstop" : "ce:mod:puppet:" + target.getUniqueId()));
        inventory.setItem(40, this.button(Material.LIME_DYE, "Unmute", NamedTextColor.GREEN, List.of(), "ce:mod:unmute:" + target.getUniqueId()));
        inventory.setItem(41, this.button(Material.LIME_DYE, "Unfreeze", NamedTextColor.GREEN, List.of(), "ce:mod:unfreeze:" + target.getUniqueId()));
        inventory.setItem(42, this.button(Material.NAME_TAG, "Roles", NamedTextColor.AQUA, List.of(), "ce:mod:roles:" + target.getUniqueId()));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:hub"));
        player.openInventory(inventory);
    }

    public void openRoleManager(Player player, OfflinePlayer target) {
        Inventory inventory = CrownsMenuHolder.create("mod-roles", 27, Component.text("Roles: " + safeName(target), NamedTextColor.GOLD));
        int slot = 10;
        for (StaffRole role : this.roles.values().stream().sorted(Comparator.comparing(StaffRole::key)).toList()) {
            inventory.setItem(slot++, this.button(Material.NAME_TAG, role.displayName(), color(role.color()), List.of(
                    Component.text("Assign " + role.displayName(), NamedTextColor.GRAY)
            ), "ce:mod:assignrole:" + target.getUniqueId() + ":" + role.key()));
        }
        inventory.setItem(16, this.button(Material.BARRIER, "Clear Role", NamedTextColor.RED, List.of(), "ce:mod:clearrole:" + target.getUniqueId()));
        inventory.setItem(22, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:mod:profile:" + target.getUniqueId()));
        player.openInventory(inventory);
    }

    public void handleGuiAction(Player player, String action) {
        String[] parts = action.split(":");
        if (!parts[1].equals("mod")) {
            return;
        }
        switch (parts[2]) {
            case "hub" -> this.openStaffHub(player);
            case "suite" -> CrownsAPI.openSuiteHome(player);
            case "reports" -> this.openReportsMenu(player);
            case "directory" -> this.openPlayerDirectory(player);
            case "analyticshub", "playtimehub" -> this.openAnalyticsHub(player);
            case "entityhub" -> this.openEntityToolsHub(player);
            case "roleself" -> this.openSelfRoleSummary(player);
            case "staffmode" -> {
                player.closeInventory();
                boolean enabled = this.toggleStaffMode(player);
                player.sendMessage(Component.text(enabled ? "Staff mode enabled." : "Staff mode disabled.", enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "vanish" -> {
                player.closeInventory();
                if (!this.hasCapability(player, StaffCapability.VANISH)) {
                    player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return;
                }
                boolean enabled = this.toggleVanish(player);
                player.sendMessage(Component.text(enabled ? "True vanish enabled." : "True vanish disabled.", enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "analyticstoday" -> {
                player.closeInventory();
                player.performCommand("ca analytics economy today");
            }
            case "playtimetop" -> {
                player.closeInventory();
                player.performCommand("ca playtime top today");
            }
            case "entitiesnow" -> {
                player.closeInventory();
                player.performCommand("ca entities");
            }
            case "clearmobsnow" -> {
                player.closeInventory();
                player.performCommand("ca clearmobs 64");
            }
            case "clearitemsnow" -> {
                player.closeInventory();
                player.performCommand("ca clearitems 64");
            }
            case "profile" -> this.openPlayerProfile(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])));
            case "roles" -> this.openRoleManager(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])));
            case "assignrole" -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(parts[3]));
                player.closeInventory();
                if (this.assignRole(player, target, parts[4])) {
                    player.sendMessage(Component.text("Assigned role to " + safeName(target) + ".", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Could not assign that role.", NamedTextColor.RED));
                }
            }
            case "clearrole" -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(parts[3]));
                player.closeInventory();
                if (this.clearRole(player, target)) {
                    player.sendMessage(Component.text("Role cleared.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("Could not clear that role.", NamedTextColor.RED));
                }
            }
            case "openinv" -> this.openInventoryView(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])));
            case "openender" -> this.openEnderChestView(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])));
            case "promptwarn" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the warning reason. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.setAwaitingAction(player.getUniqueId(), "warn:" + parts[3]);
            }
            case "promptnote" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the note. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.setAwaitingAction(player.getUniqueId(), "note:" + parts[3]);
            }
            case "promptmute" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the mute reason. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.setAwaitingAction(player.getUniqueId(), "mute:" + parts[3] + ":" + parts[4]);
            }
            case "promptban" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the ban reason. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.setAwaitingAction(player.getUniqueId(), "ban:" + parts[3] + ":" + parts[4]);
            }
            case "freeze" -> {
                player.closeInventory();
                this.freeze(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])), "Frozen by staff", "gui");
                player.sendMessage(Component.text("Player frozen.", NamedTextColor.GREEN));
            }
            case "unfreeze" -> {
                player.closeInventory();
                this.unfreeze(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])), "gui");
                player.sendMessage(Component.text("Player unfrozen.", NamedTextColor.GREEN));
            }
            case "unmute" -> {
                player.closeInventory();
                this.unmute(player, Bukkit.getOfflinePlayer(UUID.fromString(parts[3])), "gui");
                player.sendMessage(Component.text("Player unmuted.", NamedTextColor.GREEN));
            }
            case "kick" -> {
                player.closeInventory();
                Player target = Bukkit.getPlayer(UUID.fromString(parts[3]));
                if (target != null) {
                    this.kick(player, target, "Removed by staff", "gui");
                } else {
                    player.sendMessage(Component.text("Target must be online to kick.", NamedTextColor.RED));
                }
            }
            case "puppet" -> {
                player.closeInventory();
                Player target = Bukkit.getPlayer(UUID.fromString(parts[3]));
                if (target != null && this.startPuppeteer(player, target)) {
                    player.sendMessage(Component.text("Now puppeteering " + target.getName() + ".", NamedTextColor.LIGHT_PURPLE));
                } else {
                    player.sendMessage(Component.text("Could not start puppeteering.", NamedTextColor.RED));
                }
            }
            case "puppetstop" -> {
                player.closeInventory();
                if (this.stopPuppeteer(player.getUniqueId(), "stopped by staff")) {
                    player.sendMessage(Component.text("Puppeteering ended.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("You are not puppeteering anyone.", NamedTextColor.RED));
                }
            }
            case "reportclaim" -> {
                this.claimReport(Long.parseLong(parts[3]), player);
                this.openReportsMenu(player);
            }
            case "reportdismiss" -> {
                this.resolveReport(Long.parseLong(parts[3]), player, "dismissed", "Dismissed by staff");
                this.openReportsMenu(player);
            }
        }
    }

    private void loadRoles() {
        this.roles.clear();
        if (this.plugin.getConfig().isConfigurationSection("moderation.roles")) {
            for (String key : this.plugin.getConfig().getConfigurationSection("moderation.roles").getKeys(false)) {
                String path = "moderation.roles." + key;
                String display = this.plugin.getConfig().getString(path + ".display", key);
                String color = this.plugin.getConfig().getString(path + ".color", "#55ffff");
                Set<StaffCapability> capabilities = EnumSet.noneOf(StaffCapability.class);
                for (String capabilityKey : this.plugin.getConfig().getStringList(path + ".capabilities")) {
                    StaffCapability capability = StaffCapability.fromKey(capabilityKey);
                    if (capability != null) {
                        capabilities.add(capability);
                    }
                }
                if (!capabilities.isEmpty()) {
                    this.roles.put(key.toLowerCase(Locale.ROOT), new StaffRole(key.toLowerCase(Locale.ROOT), display, color, capabilities));
                }
            }
        }
        if (this.roles.isEmpty()) {
            this.roles.put("trial_mod", new StaffRole("trial_mod", "Trial Mod", "#63c7ff", EnumSet.of(StaffCapability.INSPECT, StaffCapability.REPORTS, StaffCapability.WARN, StaffCapability.NOTE, StaffCapability.FREEZE, StaffCapability.STAFFMODE)));
            this.roles.put("moderator", new StaffRole("moderator", "Moderator", "#57f287", EnumSet.of(StaffCapability.INSPECT, StaffCapability.INVENTORY_EDIT, StaffCapability.REPORTS, StaffCapability.WARN, StaffCapability.NOTE, StaffCapability.KICK, StaffCapability.MUTE, StaffCapability.FREEZE, StaffCapability.STAFFMODE)));
            this.roles.put("admin", new StaffRole("admin", "Admin", "#ff5e5b", EnumSet.allOf(StaffCapability.class)));
        }
    }

    private void loadAssignedRoles() {
        this.assignedRoles.clear();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("SELECT player_uuid, role_key FROM staff_roles")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    this.assignedRoles.put(UUID.fromString(resultSet.getString("player_uuid")), resultSet.getString("role_key"));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load staff roles failed: " + exception.getMessage());
        }
    }

    private void loadActiveMutes() {
        this.activeMutes.clear();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("SELECT * FROM moderation_mutes")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID targetId = UUID.fromString(resultSet.getString("target_uuid"));
                    this.activeMutes.put(targetId, new ActiveMute(targetId, resultSet.getString("target_name"), resultSet.getString("reason"), resultSet.getLong("expires_at")));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load mutes failed: " + exception.getMessage());
        }
    }

    private void loadActiveFreezes() {
        this.activeFreezes.clear();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("SELECT * FROM moderation_freezes")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID targetId = UUID.fromString(resultSet.getString("target_uuid"));
                    this.activeFreezes.put(targetId, new ActiveFreeze(targetId, resultSet.getString("target_name"), resultSet.getString("reason")));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load freezes failed: " + exception.getMessage());
        }
    }

    private boolean recordSimpleAction(Player actor, OfflinePlayer target, String type, String reason, String source, boolean notifyTarget) {
        if (target.getUniqueId() == null) {
            return false;
        }
        this.logAction(actor, target.getUniqueId(), target.getName(), type, reason, 0L, false, source);
        if (notifyTarget) {
            this.notifyTarget(target, "Staff action: " + type, reason);
        }
        return true;
    }

    private void notifyTarget(OfflinePlayer target, String title, String body) {
        if (target.getUniqueId() == null) {
            return;
        }
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Component.text(title, NamedTextColor.RED));
            if (body != null && !body.isBlank()) {
                online.sendMessage(Component.text(body, NamedTextColor.GRAY));
            }
        }
        this.plugin.getInboxManager().push(target.getUniqueId(), target.getName(), "moderation", title, body);
    }

    private void pushStaffNotice(String title, String body) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (this.hasCapability(online, StaffCapability.REPORTS)) {
                online.sendMessage(Component.text(title, NamedTextColor.GOLD));
                online.sendMessage(Component.text(body, NamedTextColor.GRAY));
            }
        }
    }

    private void loadPersistedVanishStates() {
        this.vanishedPlayers.clear();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT player_uuid FROM moderation_vanish_state")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID playerId = UUID.fromString(resultSet.getString("player_uuid"));
                    this.vanishedPlayers.put(playerId, new VanishState(true));
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Vanish state load failed: " + exception.getMessage());
        }
    }

    private void logAction(Player actor, UUID targetUuid, String targetName, String actionType, String reason, long expiresAt, boolean active, String source) {
        String actorName = actor != null ? actor.getName() : "System";
        UUID actorUuid = actor != null ? actor.getUniqueId() : null;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO moderation_actions (actor_uuid, actor_name, target_uuid, target_name, action_type, reason, created_at, expires_at, resolved_at, active, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)")) {
            statement.setString(1, actorUuid != null ? actorUuid.toString() : null);
            statement.setString(2, actorName);
            statement.setString(3, targetUuid != null ? targetUuid.toString() : null);
            statement.setString(4, targetName);
            statement.setString(5, actionType);
            statement.setString(6, reason);
            statement.setLong(7, System.currentTimeMillis());
            statement.setLong(8, expiresAt);
            statement.setInt(9, active ? 1 : 0);
            statement.setString(10, source);
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Action log failed: " + exception.getMessage());
        }
    }

    private void resolveActiveActions(UUID targetUuid, String actionType) {
        if (targetUuid == null) {
            return;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE moderation_actions SET active = 0, resolved_at = ? WHERE target_uuid = ? AND action_type = ? AND active = 1")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, targetUuid.toString());
            statement.setString(3, actionType);
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void expireMute(UUID playerId) {
        ActiveMute mute = this.activeMutes.remove(playerId);
        if (mute == null) {
            return;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM moderation_mutes WHERE target_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
        this.resolveActiveActions(playerId, "mute");
    }

    private void claimReport(long reportId, Player actor) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE moderation_reports SET status = 'claimed', claimed_by_uuid = ?, claimed_by_name = ? WHERE id = ?")) {
            statement.setString(1, actor.getUniqueId().toString());
            statement.setString(2, actor.getName());
            statement.setLong(3, reportId);
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void resolveReport(long reportId, Player actor, String status, String note) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE moderation_reports SET status = ?, claimed_by_uuid = ?, claimed_by_name = ?, resolved_at = ?, resolution_note = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, actor.getUniqueId().toString());
            statement.setString(3, actor.getName());
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, note);
            statement.setLong(6, reportId);
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private PlayerStorageState loadState(OfflinePlayer target) {
        if (target.isOnline() && target.getPlayer() != null) {
            Player online = target.getPlayer();
            return new PlayerStorageState(
                    copyItems(online.getInventory().getStorageContents(), 36),
                    copyItems(online.getInventory().getArmorContents(), 4),
                    new ItemStack[]{cloneItem(online.getInventory().getItemInOffHand())},
                    copyItems(online.getEnderChest().getContents(), 27),
                    true
            );
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT inventory_data, armor_data, offhand_data, ender_data FROM player_storage WHERE player_uuid = ?")) {
            statement.setString(1, target.getUniqueId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new PlayerStorageState(
                            InventorySerialization.deserialize(resultSet.getString("inventory_data"), 36),
                            InventorySerialization.deserialize(resultSet.getString("armor_data"), 4),
                            InventorySerialization.deserialize(resultSet.getString("offhand_data"), 1),
                            InventorySerialization.deserialize(resultSet.getString("ender_data"), 27),
                            false
                    );
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load player storage failed: " + exception.getMessage());
        }
        return new PlayerStorageState(new ItemStack[36], new ItemStack[4], new ItemStack[1], new ItemStack[27], false);
    }

    private PlayerStorageState loadStoredState(UUID playerId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT inventory_data, armor_data, offhand_data, ender_data FROM player_storage WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new PlayerStorageState(
                            InventorySerialization.deserialize(resultSet.getString("inventory_data"), 36),
                            InventorySerialization.deserialize(resultSet.getString("armor_data"), 4),
                            InventorySerialization.deserialize(resultSet.getString("offhand_data"), 1),
                            InventorySerialization.deserialize(resultSet.getString("ender_data"), 27),
                            false
                    );
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Load stored state failed: " + exception.getMessage());
        }
        return null;
    }

    private void savePlayerStorage(UUID playerId, String playerName, ItemStack[] inventory, ItemStack[] armor, ItemStack[] offhand, ItemStack[] ender) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO player_storage (player_uuid, player_name, inventory_data, armor_data, offhand_data, ender_data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, InventorySerialization.serialize(copyItems(inventory, 36)));
            statement.setString(4, InventorySerialization.serialize(copyItems(armor, 4)));
            statement.setString(5, InventorySerialization.serialize(copyItems(offhand, 1)));
            statement.setString(6, InventorySerialization.serialize(copyItems(ender, 27)));
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Save player storage failed: " + exception.getMessage());
        }
    }

    private void applyStoredState(Player player) {
        PlayerStorageState state = this.loadStoredState(player.getUniqueId());
        if (state == null) {
            return;
        }
        player.getInventory().setStorageContents(copyItems(state.inventory(), 36));
        player.getInventory().setArmorContents(copyItems(state.armor(), 4));
        player.getInventory().setItemInOffHand(state.offhand().length > 0 ? cloneItem(state.offhand()[0]) : null);
        player.getEnderChest().setContents(copyItems(state.ender(), 27));
        player.updateInventory();
    }

    private void snapshotState(OfflinePlayer target, Player actor, String reason) {
        if (target.getUniqueId() == null) {
            return;
        }
        PlayerStorageState state = this.loadState(target);
        if (state == null) {
            return;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO inventory_snapshots (target_uuid, target_name, storage_type, inventory_data, armor_data, offhand_data, ender_data, actor_uuid, actor_name, created_at, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getName());
            statement.setString(3, STORAGE_INV);
            statement.setString(4, InventorySerialization.serialize(state.inventory()));
            statement.setString(5, InventorySerialization.serialize(state.armor()));
            statement.setString(6, InventorySerialization.serialize(state.offhand()));
            statement.setString(7, InventorySerialization.serialize(state.ender()));
            statement.setString(8, actor.getUniqueId().toString());
            statement.setString(9, actor.getName());
            statement.setLong(10, System.currentTimeMillis());
            statement.setString(11, reason);
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Snapshot failed: " + exception.getMessage());
        }
        int maxSnapshots = Math.max(5, this.plugin.getConfig().getInt("moderation.inventory-snapshots.max-per-player", 10));
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM inventory_snapshots WHERE target_uuid = ? AND id NOT IN (SELECT id FROM inventory_snapshots WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?)")) {
            statement.setString(1, target.getUniqueId().toString());
            statement.setString(2, target.getUniqueId().toString());
            statement.setInt(3, maxSnapshots);
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public boolean rollbackSnapshot(Player actor, OfflinePlayer target, long snapshotId) {
        if (target.getUniqueId() == null) {
            return false;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT inventory_data, armor_data, offhand_data, ender_data FROM inventory_snapshots WHERE id = ? AND target_uuid = ?")) {
            statement.setLong(1, snapshotId);
            statement.setString(2, target.getUniqueId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    ItemStack[] inventory = InventorySerialization.deserialize(resultSet.getString("inventory_data"), 36);
                    ItemStack[] armor = InventorySerialization.deserialize(resultSet.getString("armor_data"), 4);
                    ItemStack[] offhand = InventorySerialization.deserialize(resultSet.getString("offhand_data"), 1);
                    ItemStack[] ender = InventorySerialization.deserialize(resultSet.getString("ender_data"), 27);
                    this.savePlayerStorage(target.getUniqueId(), target.getName(), inventory, armor, offhand, ender);
                    Player online = target.getPlayer();
                    if (online != null) {
                        this.applyStoredState(online);
                    }
                    this.logAction(actor, target.getUniqueId(), target.getName(), "inventory_rollback", "Rolled back inventory snapshot #" + snapshotId, 0L, false, "command");
                    return true;
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Rollback failed: " + exception.getMessage());
        }
        return false;
    }

    public List<Long> getSnapshotIds(UUID targetId) {
        List<Long> ids = new ArrayList<>();
        if (targetId == null) {
            return ids;
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT id FROM inventory_snapshots WHERE target_uuid = ? ORDER BY created_at DESC LIMIT 10")) {
            statement.setString(1, targetId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong("id"));
                }
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private void enableStaffMode(Player player) {
        this.staffModeStates.put(player.getUniqueId(), new StaffModeState(player.getAllowFlight(), player.isFlying(), player.getGameMode()));
        player.setAllowFlight(true);
        player.setFlying(true);
        this.syncVisibilityFor(player);
        this.syncViewerForAll(player);
    }

    private void disableStaffMode(Player player) {
        StaffModeState state = this.staffModeStates.remove(player.getUniqueId());
        if (state != null) {
            player.setAllowFlight(state.allowFlight());
            player.setFlying(state.flying());
            player.setGameMode(state.gameMode());
        }
        this.syncVisibilityFor(player);
        this.syncViewerForAll(player);
    }

    private boolean enableVanish(Player player, String source) {
        if (!this.plugin.getConfig().getBoolean("moderation.vanish.enabled", true)) {
            return false;
        }
        this.vanishedPlayers.put(player.getUniqueId(), new VanishState(player.isCollidable()));
        player.setCollidable(false);
        this.saveVanishState(player);
        this.syncVisibilityFor(player);
        this.syncViewerForAll(player);
        this.broadcastFakePresence(player, false);
        this.logAction(player, player.getUniqueId(), player.getName(), "vanish_on", "True vanish enabled", 0L, true, source);
        return true;
    }

    private void disableVanish(Player player, String source) {
        VanishState state = this.vanishedPlayers.remove(player.getUniqueId());
        if (state != null) {
            player.setCollidable(state.collidable());
        }
        this.clearVanishState(player.getUniqueId());
        this.syncVisibilityFor(player);
        this.syncViewerForAll(player);
        this.broadcastFakePresence(player, true);
        this.logAction(player, player.getUniqueId(), player.getName(), "vanish_off", "True vanish disabled", 0L, false, source);
    }

    private void saveVanishState(Player player) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT OR REPLACE INTO moderation_vanish_state (player_uuid, player_name, stored_at) VALUES (?, ?, ?)")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Vanish state save failed: " + exception.getMessage());
        }
    }

    private void clearVanishState(UUID playerId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM moderation_vanish_state WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Moderation] Vanish state clear failed: " + exception.getMessage());
        }
    }

    private void broadcastFakePresence(Player player, boolean joined) {
        if (!this.plugin.getConfig().getBoolean("moderation.vanish.fake-presence-messages", true)) {
            return;
        }
        Bukkit.broadcast(Component.text(player.getName() + (joined ? " joined the game" : " left the game"), NamedTextColor.YELLOW));
    }

    private void allowPuppetMovement(UUID playerId) {
        this.puppetMovementWindows.put(playerId, System.currentTimeMillis() + 250L);
    }

    private boolean isPuppetMovementAllowed(UUID playerId) {
        long expiresAt = this.puppetMovementWindows.getOrDefault(playerId, 0L);
        if (expiresAt <= System.currentTimeMillis()) {
            this.puppetMovementWindows.remove(playerId);
            return false;
        }
        return true;
    }

    private void syncViewerForAll(Player viewer) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.applyVisibility(viewer, online);
        }
    }

    private void syncVisibilityFor(Player subject) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            this.applyVisibility(viewer, subject);
        }
    }

    private void applyVisibility(Player viewer, Player subject) {
        if (viewer == null || subject == null || viewer.equals(subject)) {
            return;
        }
        if (this.shouldHideFrom(viewer, subject)) {
            viewer.hidePlayer(this.plugin, subject);
        } else {
            viewer.showPlayer(this.plugin, subject);
        }
    }

    private boolean shouldHideFrom(Player viewer, Player subject) {
        if (this.isVanished(subject.getUniqueId())) {
            return !this.canSeeVanished(viewer, subject);
        }
        if (this.isInStaffMode(subject.getUniqueId())) {
            return !this.hasCapability(viewer, StaffCapability.STAFFMODE);
        }
        return false;
    }

    private boolean canSeeVanished(Player viewer, Player vanished) {
        if (viewer.equals(vanished)) {
            return true;
        }
        if (!this.plugin.getConfig().getBoolean("moderation.vanish.staff-can-see-vanished", true)) {
            return false;
        }
        return this.hasCapability(viewer, StaffCapability.VANISH) || this.hasCapability(viewer, StaffCapability.STAFFMODE);
    }

    private ModerationReport readReport(ResultSet resultSet) throws java.sql.SQLException {
        return new ModerationReport(
                resultSet.getLong("id"),
                uuid(resultSet.getString("reporter_uuid")),
                resultSet.getString("reporter_name"),
                uuid(resultSet.getString("target_uuid")),
                resultSet.getString("target_name"),
                resultSet.getString("reason"),
                resultSet.getString("status"),
                resultSet.getString("claimed_by_name"),
                resultSet.getLong("created_at")
        );
    }

    private UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private ItemStack[] copyItems(ItemStack[] items, int size) {
        ItemStack[] copy = new ItemStack[size];
        for (int i = 0; i < Math.min(size, items.length); i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private String safeName(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString().substring(0, 8) : target.getName();
    }

    private String describeRole(UUID playerId) {
        StaffRole role = this.getAssignedRole(playerId);
        return role == null ? "No assigned role" : role.displayName();
    }

    private NamedTextColor color(String hex) {
        TextColor color = TextColor.fromHexString(hex);
        if (color instanceof NamedTextColor named) {
            return named;
        }
        return NamedTextColor.AQUA;
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action) {
        return this.button(material, name, color, lore, action, null);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action, String modelPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        List<Component> fullLore = new ArrayList<>(lore);
        fullLore.add(Component.text(action, NamedTextColor.DARK_GRAY));
        meta.lore(fullLore);
        PackModelHelper.apply(meta, modelPath);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack info(Material material, String name, String value) {
        return this.info(material, name, value, null);
    }

    private ItemStack info(Material material, String name, String value, String modelPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(List.of(Component.text(value, NamedTextColor.GRAY)));
        PackModelHelper.apply(meta, modelPath);
        item.setItemMeta(meta);
        return item;
    }

    private record ActiveMute(UUID targetUuid, String targetName, String reason, long expiresAt) {
    }

    private record ActiveFreeze(UUID targetUuid, String targetName, String reason) {
    }

    private record PlayerStorageState(ItemStack[] inventory, ItemStack[] armor, ItemStack[] offhand, ItemStack[] ender, boolean online) {
    }

    private record InventorySession(UUID viewerUuid, UUID targetUuid, String targetName, String type, Inventory inventory, boolean online, ItemStack[] cachedEnder) {
    }

    private record StaffModeState(boolean allowFlight, boolean flying, GameMode gameMode) {
    }

    private record VanishState(boolean collidable) {
    }

    private record PuppeteerSession(UUID controllerUuid, String controllerName, UUID targetUuid, String targetName,
                                    long startedAt, GameMode originalGameMode, Location originalLocation,
                                    boolean originalAllowFlight, boolean originalFlying, boolean originalInvulnerable,
                                    boolean originalInvisible, Entity originalSpectatorTarget) {
    }
}
