package com.xkstudios.crowns.gui;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.ResourcePackService;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.data.PlayerData;
import com.xkstudios.crowns.inbox.InboxEntry;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter ALERT_TIME = DateTimeFormatter.ofPattern("MMM d h:mm a");
    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final int[] homeSlots = {10, 12, 14, 16};

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
        inventory.setItem(4, this.button(Material.PLAYER_HEAD, "Suite Profile", NamedTextColor.YELLOW, List.of(
                Component.text("See your cross-suite highlights.", NamedTextColor.GRAY)
        ), "suite:profile", "lowlight/suite/profile"));
        inventory.setItem(22, this.button(Material.BOOK, "Resource Pack", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text(CrownsAPI.getResourcePackService() == null
                        ? "Resource-pack service offline."
                        : CrownsAPI.getResourcePackService().getStatusSummary(), NamedTextColor.GRAY)
        ), "suite:pack", "lowlight/suite/resource_pack"));
        inventory.setItem(26, this.button(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "suite:close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void openProfile(Player player) {
        Inventory inventory = CrownsMenuHolder.create("suite-profile", 54, Component.text("Suite Profile", NamedTextColor.GOLD));
        PlayerData playerData = CrownsAPI.getPlayerData() == null ? null : CrownsAPI.getPlayerData().getOrCreate(player.getUniqueId(), player.getName());
        int unread = CrownsAPI.getInbox() == null ? 0 : 0;
        if (this.plugin instanceof com.xkstudios.crowns.api.CrownsAPIPlugin apiPlugin) {
            unread = apiPlugin.getInboxManager().getUnreadCount(player.getUniqueId());
        }
        inventory.setItem(10, this.info(Material.PLAYER_HEAD, player.getName(), NamedTextColor.YELLOW, List.of(
                Component.text("Installed sections: " + CrownsAPI.getSections().stream().filter(section -> section.isVisibleTo(player)).count(), NamedTextColor.GRAY),
                Component.text("Unread inbox: " + unread, NamedTextColor.GRAY)
        ), "lowlight/suite/profile"));
        inventory.setItem(12, this.info(Material.GOLD_INGOT, "Economy", NamedTextColor.GOLD, List.of(
                Component.text(playerData == null || CrownsAPI.getEconomy() == null
                        ? "Balance unavailable."
                        : "Balance: " + CrownsAPI.getEconomy().formatCurrency(playerData.getBalance()), NamedTextColor.GRAY),
                Component.text(playerData == null ? "No earnings data." : "Total earned: " + (CrownsAPI.getEconomy() == null
                        ? String.valueOf(playerData.getTotalEarned())
                        : CrownsAPI.getEconomy().formatCurrency(playerData.getTotalEarned())), NamedTextColor.GRAY)
        ), "lowlight/suite/economy"));
        List<Component> eventLore = new ArrayList<>();
        eventLore.add(Component.text(CrownsAPI.getEvents() == null ? "No active event provider." : CrownsAPI.getEvents().getActiveEventLabel(), NamedTextColor.GRAY));
        eventLore.add(Component.text(CrownsAPI.getEvents() == null ? "No event status." : CrownsAPI.getEvents().getPlayerProgressSummary(player.getUniqueId(), player.getName()), NamedTextColor.GRAY));
        if (CrownsAPI.getEvents() != null) {
            List<String> liveSummaries = CrownsAPI.getEvents().getLiveEventSummaries();
            if (liveSummaries.isEmpty()) {
                eventLore.add(Component.text("No admin-run live moments active.", NamedTextColor.DARK_GRAY));
            } else {
                for (String summary : liveSummaries.stream().limit(2).toList()) {
                    eventLore.add(Component.text(summary, NamedTextColor.YELLOW));
                }
            }
        }
        inventory.setItem(14, this.info(Material.DRAGON_HEAD, "Events", NamedTextColor.LIGHT_PURPLE, eventLore, "lowlight/suite/events"));
        inventory.setItem(16, this.info(Material.PAPER, "Recent Alerts", NamedTextColor.AQUA, this.alertLore(), "lowlight/suite/alerts"));
        inventory.setItem(28, this.info(Material.BOOK, "Recent Inbox", NamedTextColor.WHITE, this.inboxLore(player), "lowlight/suite/inbox"));
        inventory.setItem(49, this.backToHomeButton());
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openResourcePack(Player player) {
        ResourcePackService service = CrownsAPI.getResourcePackService();
        Inventory inventory = CrownsMenuHolder.create("suite-resource-pack", 54, Component.text("Crowns Resource Pack", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(11, this.info(Material.BOOK, "Pack Status", NamedTextColor.YELLOW, List.of(
                Component.text(service == null ? "Resource-pack service offline." : service.getStatusSummary(), NamedTextColor.GRAY),
                Component.text(service == null ? "Version: unknown" : "Version: " + service.getVersion(), NamedTextColor.GRAY)
        ), "lowlight/suite/resource_pack"));
        inventory.setItem(13, this.info(Material.NAME_TAG, "Install", NamedTextColor.AQUA, List.of(
                Component.text("Click Share to receive the pack link in chat.", NamedTextColor.GRAY),
                Component.text("This suite uses manual pack delivery in 1.2.0.", NamedTextColor.GRAY)
        ), "lowlight/suite/resource_pack"));
        inventory.setItem(15, this.info(Material.PAPER, "Hash", NamedTextColor.WHITE, List.of(
                Component.text(service == null || service.getSha1().isBlank() ? "No SHA1 configured." : service.getSha1(), NamedTextColor.DARK_GRAY)
        ), "lowlight/suite/resource_pack"));
        inventory.setItem(30, this.button(Material.EMERALD, "Share Pack Info", NamedTextColor.GREEN, List.of(
                Component.text("Send yourself the current pack details.", NamedTextColor.GRAY)
        ), "suite:pack:share", "lowlight/suite/resource_pack_share"));
        inventory.setItem(32, this.button(Material.BELL, "Broadcast Pack Info", NamedTextColor.GOLD, List.of(
                Component.text("Admin-only manual pack reminder.", NamedTextColor.GRAY)
        ), "suite:pack:broadcast", "lowlight/suite/resource_pack_broadcast"));
        inventory.setItem(49, this.backToHomeButton());
        this.fillBorder(inventory);
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

    private List<Component> alertLore() {
        List<Component> lore = new ArrayList<>();
        for (var alert : CrownsAPI.getRecentAlerts(4)) {
            lore.add(Component.text(alert.title(), NamedTextColor.YELLOW));
            if (alert.body() != null && !alert.body().isBlank()) {
                lore.add(Component.text(alert.body(), NamedTextColor.GRAY));
            }
            lore.add(Component.text(ALERT_TIME.format(Instant.ofEpochMilli(alert.createdAt()).atZone(ZoneId.systemDefault())), NamedTextColor.DARK_GRAY));
        }
        if (lore.isEmpty()) {
            lore.add(Component.text("No suite alerts yet.", NamedTextColor.GRAY));
        }
        return lore;
    }

    private List<Component> inboxLore(Player player) {
        List<Component> lore = new ArrayList<>();
        if (!(this.plugin instanceof com.xkstudios.crowns.api.CrownsAPIPlugin apiPlugin)) {
            lore.add(Component.text("Inbox unavailable.", NamedTextColor.GRAY));
            return lore;
        }
        List<InboxEntry> entries = apiPlugin.getInboxManager().getEntries(player.getUniqueId(), 3);
        for (InboxEntry entry : entries) {
            lore.add(Component.text(entry.title(), entry.unread() ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
            if (entry.body() != null && !entry.body().isBlank()) {
                lore.add(Component.text(entry.body(), NamedTextColor.GRAY));
            }
        }
        if (lore.isEmpty()) {
            lore.add(Component.text("No inbox entries yet.", NamedTextColor.GRAY));
        }
        return lore;
    }
}
