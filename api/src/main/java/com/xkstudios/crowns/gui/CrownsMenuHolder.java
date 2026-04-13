package com.xkstudios.crowns.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class CrownsMenuHolder implements InventoryHolder {
    private final String key;
    private Inventory inventory;

    private CrownsMenuHolder(String key) {
        this.key = key;
    }

    public static Inventory create(String key, int size, Component title) {
        CrownsMenuHolder holder = new CrownsMenuHolder(key);
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    public static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof CrownsMenuHolder;
    }

    public String key() {
        return this.key;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
