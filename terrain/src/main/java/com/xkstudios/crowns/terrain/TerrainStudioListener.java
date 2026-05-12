package com.xkstudios.crowns.terrain;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class TerrainStudioListener implements Listener {
    private final CrownsTerrainPlugin plugin;

    public TerrainStudioListener(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("crowns.terrain.admin") && !player.isOp()) {
            return;
        }
        if (!this.plugin.getStudioManager().isStudioWand(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            player.sendMessage(Component.text("Click a block to set a Structure Studio position.", NamedTextColor.YELLOW));
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            this.plugin.getStudioManager().setPosition(player, 1, clicked.getLocation());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            this.plugin.getStudioManager().setPosition(player, 2, clicked.getLocation());
        }
    }
}
