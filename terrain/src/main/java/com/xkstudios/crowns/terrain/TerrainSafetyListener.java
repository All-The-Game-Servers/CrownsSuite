package com.xkstudios.crowns.terrain;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class TerrainSafetyListener implements Listener {
    private final CrownsTerrainPlugin plugin;

    public TerrainSafetyListener(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location target = event.getTo();
        if (target == null || target.getWorld() == null) {
            return;
        }
        int floor = this.plugin.getTerrainManager().floorForWorld(target.getWorld().getName());
        if (floor <= 0 || this.plugin.getTerrainManager().isFloorReadyForPlayers(floor)) {
            return;
        }
        if (event.getPlayer().hasPermission("crowns.terrain.admin") || event.getPlayer().isOp()) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("That Crowns floor is still being generated. Ask staff to run /cterrain admin generate " + floor + " and wait for critical-ready.", NamedTextColor.RED));
    }
}
