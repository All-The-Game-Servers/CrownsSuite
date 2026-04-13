package com.xkstudios.crowns.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public final class MenuManager {
    private MenuManager() {
    }

    public static boolean isCrownsMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof CrownsMenuHolder;
    }

    public static boolean isCrownsMenuClick(InventoryClickEvent event) {
        return event != null && isCrownsMenu(event.getView().getTopInventory());
    }

    public static void protectTopInventoryClick(InventoryClickEvent event) {
        if (isCrownsMenuClick(event) && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
        }
    }
}
