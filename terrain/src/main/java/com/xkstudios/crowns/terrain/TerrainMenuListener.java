package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class TerrainMenuListener implements Listener {
    private final CrownsTerrainPlugin plugin;

    public TerrainMenuListener(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        String action = CrownsAPI.getSuiteGui().readAction(event.getCurrentItem());
        if (action == null || action.isBlank()) {
            return;
        }
        if (action.equals("terrain:hub")) {
            event.setCancelled(true);
            this.plugin.getMenuManager().openHub(player);
        }
    }
}
