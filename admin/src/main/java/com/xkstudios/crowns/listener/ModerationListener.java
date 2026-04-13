package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class ModerationListener implements Listener {
    private final CrownsPlugin plugin;

    public ModerationListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.plugin.getConfig().getBoolean("moderation.vanish.silent-join-quit", true)
                && this.plugin.getModerationManager().isVanished(event.getPlayer().getUniqueId())) {
            event.joinMessage(null);
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.getModerationManager().handleJoin(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (this.plugin.getConfig().getBoolean("moderation.vanish.silent-join-quit", true)
                && this.plugin.getModerationManager().isVanished(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
        this.plugin.getModerationManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPromptChat(AsyncPlayerChatEvent event) {
        String action = this.plugin.getModerationManager().getAwaitingAction(event.getPlayer().getUniqueId());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        this.plugin.getModerationManager().clearAwaitingAction(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.getModerationManager().handleChatPrompt(event.getPlayer(), action, event.getMessage()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMutedChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!this.plugin.getModerationManager().isMuted(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text("You are muted: " + this.plugin.getModerationManager().mutedReason(player.getUniqueId()), NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        this.plugin.getModerationManager().handlePuppetLook(event.getPlayer());
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (!this.plugin.getModerationManager().isMovementLocked(player.getUniqueId())) {
            return;
        }
        event.setTo(event.getFrom());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenDrop(PlayerDropItemEvent event) {
        if (this.plugin.getModerationManager().isControlLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("You cannot do that while frozen.", NamedTextColor.RED));
        } else if (this.plugin.getModerationManager().isInStaffMode(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenCommand(PlayerCommandPreprocessEvent event) {
        if (!this.plugin.getModerationManager().isControlLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        String message = event.getMessage().toLowerCase();
        for (String blocked : this.plugin.getConfig().getStringList("moderation.freeze.blocked-commands")) {
            if (message.startsWith(blocked.toLowerCase())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Component.text("You cannot use that command while frozen.", NamedTextColor.RED));
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (this.plugin.getModerationManager().handleInventoryEditorClick(event)) {
            return;
        }
        if (this.plugin.getModerationManager().isControlLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (this.plugin.getModerationManager().handleInventoryEditorDrag(event)) {
            return;
        }
        if (this.plugin.getModerationManager().isControlLocked(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            this.plugin.getModerationManager().handleInventoryEditorClose(player);
        }
    }

    @EventHandler
    public void onPlayerInput(PlayerInputEvent event) {
        this.plugin.getModerationManager().handlePuppetInput(event.getPlayer(), event.getInput());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (this.plugin.getModerationManager().isControlLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (this.plugin.getModerationManager().isControlLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && this.plugin.getModerationManager().isControlLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        this.plugin.getModerationManager().handlePuppetDeath(event.getEntity().getUniqueId());
    }
}
