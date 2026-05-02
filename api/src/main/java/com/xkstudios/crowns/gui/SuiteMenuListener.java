package com.xkstudios.crowns.gui;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.ResourcePackService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class SuiteMenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.DOUBLE_CLICK || event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
        }
        String action = CrownsAPI.getSuiteGui().readAction(event.getCurrentItem());
        if (action == null || action.isBlank()) {
            return;
        }
        if (action.equals("suite:close")) {
            player.closeInventory();
        } else if (action.equals("suite:home")) {
            CrownsAPI.openSuiteHome(player);
        } else if (action.equals("suite:profile")) {
            CrownsAPI.getSuiteGui().openProfile(player);
        } else if (action.equals("suite:status")) {
            CrownsAPI.getSuiteGui().openStatus(player);
        } else if (action.equals("suite:pack")) {
            CrownsAPI.getSuiteGui().openResourcePack(player);
        } else if (action.equals("suite:pack:share")) {
            ResourcePackService service = CrownsAPI.getResourcePackService();
            if (service != null) {
                player.closeInventory();
                service.sendManualInstall(player);
            }
        } else if (action.equals("suite:pack:broadcast")) {
            ResourcePackService service = CrownsAPI.getResourcePackService();
            if (service != null && (player.isOp() || player.hasPermission("crowns.admin"))) {
                player.closeInventory();
                service.broadcastManualInstall();
            }
        } else if (action.startsWith("suite:open:")) {
            CrownsAPI.getSuiteGui().openSection(player, action.substring("suite:open:".length()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
