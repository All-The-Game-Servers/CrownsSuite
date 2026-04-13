package com.xkstudios.crowns.gui;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class SuiteGuiManager {
    public static final String HOME_KEY = "suite-home";
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final int[] homeSlots = {10, 12, 14, 16, 22, 4};

    public SuiteGuiManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "suite_action");
    }

    public void openHome(Player player) {
        Inventory inventory = CrownsMenuHolder.create(HOME_KEY, 27, Component.text("Crowns Suite", NamedTextColor.GOLD));
        this.fillBorder(inventory);
        List<SuiteSection> visibleSections = new ArrayList<>(CrownsAPI.getSections().stream()
                .filter(section -> section.isVisibleTo(player))
                .toList());
        for (int i = 0; i < visibleSections.size() && i < this.homeSlots.length; i++) {
            SuiteSection section = visibleSections.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Open the " + section.displayName() + " hub.", NamedTextColor.GRAY));
            String badge = section.badge(player);
            if (badge != null && !badge.isBlank()) {
                lore.add(Component.text(badge, NamedTextColor.YELLOW));
            }
            inventory.setItem(this.homeSlots[i], this.button(section.icon(), section.displayName(), NamedTextColor.AQUA, lore, "suite:open:" + section.key(), section.modelPath()));
        }
        inventory.setItem(26, this.button(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "suite:close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void openSection(Player player, String key) {
        SuiteSection section = CrownsAPI.getSection(key);
        if (section == null || !section.isVisibleTo(player)) {
            openHome(player);
            return;
        }
        section.opener().open(player);
    }

    public String readAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.actionKey, PersistentDataType.STRING);
    }

    public ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action) {
        return this.button(material, name, color, lore, action, null);
    }

    public ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action, String modelPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore == null ? List.of() : lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        PackModelHelper.apply(meta, modelPath);
        if (action != null) {
            meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, action);
        }
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack withAction(ItemStack item, String action) {
        if (item == null || action == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack info(Material material, String name, NamedTextColor color, List<Component> lore) {
        return this.button(material, name, color, lore, null);
    }

    public ItemStack info(Material material, String name, NamedTextColor color, List<Component> lore, String modelPath) {
        return this.button(material, name, color, lore, null, modelPath);
    }

    public ItemStack backToHomeButton() {
        return this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Suite.", NamedTextColor.GRAY)
        ), "suite:home", "lowlight/suite/nav_back");
    }

    public void fillBorder(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) != null) {
                continue;
            }
            boolean border = slot < 9 || slot >= inventory.getSize() - 9 || slot % 9 == 0 || slot % 9 == 8;
            if (border) {
                inventory.setItem(slot, this.info(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, List.of()));
            }
        }
    }

    public boolean isSuiteMenu(Inventory inventory) {
        return CrownsMenuHolder.isMenu(inventory)
                && inventory.getHolder() instanceof CrownsMenuHolder holder
                && Objects.equals(holder.key(), HOME_KEY);
    }
}
