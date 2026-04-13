package com.xkstudios.crowns.gui;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.event.EventManager;
import com.xkstudios.crowns.event.EventManager.CollectorEntry;
import com.xkstudios.crowns.event.EventManager.FirstDiscovery;
import com.xkstudios.crowns.event.EventManager.MilestoneStatus;
import com.xkstudios.crowns.event.EventManager.RewardStatus;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class EventMenuManager {
    private final CrownsPlugin plugin;

    public EventMenuManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("events-selector", 54, Component.text("Server Events", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(11, this.eventCard("nether-opening", Material.NETHER_BRICK, NamedTextColor.RED, "lowlight/suite/nether_week"));
        inventory.setItem(13, this.eventCard("end-opening", Material.END_STONE, NamedTextColor.LIGHT_PURPLE, "lowlight/suite/endfall_week"));
        inventory.setItem(15, CrownsAPI.getSuiteGui().button(Material.DRAGON_EGG, "The Lowlight God's Release", NamedTextColor.DARK_PURPLE, List.of(
                Component.text("A future event slot reserved for later.", NamedTextColor.GRAY)
        ), "events:placeholder:lowlight-god", "lowlight/suite/lowlight_god"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().backToHomeButton());
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openEventHub(Player player, String eventKey) {
        EventManager manager = this.plugin.getEventManager();
        boolean live = manager.getActiveEventKey().equalsIgnoreCase(eventKey);
        Inventory inventory = CrownsMenuHolder.create("events-hub-" + eventKey, 54, Component.text(manager.getMenuLabel(eventKey), NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(10, CrownsAPI.getSuiteGui().button(Material.ENDER_EYE, "Status", NamedTextColor.AQUA, List.of(
                Component.text(live ? manager.getStatusLabel() : "Archive View", NamedTextColor.YELLOW),
                Component.text(live ? manager.getStatusDescription() : "This event page is view-only right now.", NamedTextColor.GRAY)
        ), "events:view:" + eventKey, live ? "lowlight/suite/event_live" : "lowlight/suite/event_archive"));
        inventory.setItem(12, CrownsAPI.getSuiteGui().button(Material.ENDER_CHEST, "Rewards", NamedTextColor.GOLD, List.of(
                Component.text(live ? "Browse claimable and locked rewards." : "Browse archived reward requirements.", NamedTextColor.GRAY)
        ), "events:rewards:" + eventKey, "lowlight/suite/event_rewards"));
        inventory.setItem(14, CrownsAPI.getSuiteGui().button(Material.BOOK, "Guide", NamedTextColor.GREEN, List.of(
                Component.text("How this event works.", NamedTextColor.GRAY)
        ), "events:guide:" + eventKey, "lowlight/suite/event_guide"));
        if (live) {
            inventory.setItem(16, CrownsAPI.getSuiteGui().button(Material.BLAZE_POWDER, "Turn In Hand", NamedTextColor.RED, List.of(
                    Component.text("Turn in relics from your hand.", NamedTextColor.GRAY)
            ), "events:turnin:hand", "lowlight/suite/event_turnin_hand"));
            inventory.setItem(28, CrownsAPI.getSuiteGui().button(Material.SHULKER_SHELL, "Turn In Inventory", NamedTextColor.YELLOW, List.of(
                    Component.text("Turn in every relic in your inventory.", NamedTextColor.GRAY)
            ), "events:turnin:all", "lowlight/suite/event_turnin_all"));
        } else {
            inventory.setItem(16, CrownsAPI.getSuiteGui().info(Material.GRAY_DYE, "Archive", NamedTextColor.WHITE, List.of(
                    Component.text("Turn-ins are only enabled for the live event.", NamedTextColor.GRAY)
            ), "lowlight/suite/event_archive"));
            inventory.setItem(28, CrownsAPI.getSuiteGui().info(Material.GRAY_DYE, "Read Only", NamedTextColor.WHITE, List.of(
                    Component.text("You can browse rewards and history here.", NamedTextColor.GRAY)
            ), "lowlight/suite/event_archive"));
        }
        inventory.setItem(30, CrownsAPI.getSuiteGui().info(Material.AMETHYST_SHARD, "Your Progress", NamedTextColor.WHITE, List.of(
                Component.text("Relics turned in: " + manager.getProgress(eventKey, player.getUniqueId(), player.getName()).relics(), NamedTextColor.GRAY),
                Component.text("Server total: " + manager.getTotalRelics(eventKey), NamedTextColor.GRAY)
        )));
        inventory.setItem(32, CrownsAPI.getSuiteGui().info(Material.DRAGON_HEAD, "Top Collectors", NamedTextColor.WHITE, this.collectorLore(manager.getTopCollectors(eventKey, 3))));
        inventory.setItem(34, CrownsAPI.getSuiteGui().info(Material.NETHER_STAR, "Milestones", NamedTextColor.WHITE, this.milestoneLore(manager.getMilestones(eventKey))));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to event selection.", NamedTextColor.GRAY)
        ), "events:selector", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openRewards(Player player, String eventKey) {
        EventManager manager = this.plugin.getEventManager();
        boolean live = manager.getActiveEventKey().equalsIgnoreCase(eventKey);
        Inventory inventory = CrownsMenuHolder.create("events-rewards-" + eventKey, 54, Component.text(manager.getMenuLabel(eventKey) + " Rewards", NamedTextColor.GOLD));
        int slot = 10;
        for (RewardStatus status : manager.getRewardStatuses(eventKey, player)) {
            if (slot >= 44) {
                break;
            }
            ItemStack item = manager.createRewardPreview(eventKey, status);
            if (live && !status.claimed()) {
                item = CrownsAPI.getSuiteGui().withAction(item, "events:claim:" + status.key());
            }
            inventory.setItem(slot, item);
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to the event page.", NamedTextColor.GRAY)
        ), "events:view:" + eventKey, "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGuide(Player player, String eventKey) {
        EventManager manager = this.plugin.getEventManager();
        Inventory inventory = CrownsMenuHolder.create("events-guide-" + eventKey, 54, Component.text(manager.getGuideTitle(eventKey), NamedTextColor.GREEN));
        inventory.setItem(11, CrownsAPI.getSuiteGui().info(Material.BOOK, "Quick Start", NamedTextColor.AQUA, List.of(
                Component.text(manager.isEndEvent(eventKey) ? "1. Explore the End and claim survey caches." : "1. Hunt relics across the Nether.", NamedTextColor.GRAY),
                Component.text("2. Turn relics in if this event is live.", NamedTextColor.GRAY),
                Component.text("3. Claim rewards once you unlock them.", NamedTextColor.GRAY)
        ), "lowlight/suite/event_guide"));
        inventory.setItem(13, CrownsAPI.getSuiteGui().info(Material.CHEST, "Relics", NamedTextColor.YELLOW, List.of(
                Component.text("Relics are physical event items.", NamedTextColor.GRAY),
                Component.text("Finding them does not score points until you turn them in.", NamedTextColor.GRAY)
        )));
        inventory.setItem(15, CrownsAPI.getSuiteGui().info(Material.COMPASS, "Progress", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Your turn-ins push both personal rewards and server milestones.", NamedTextColor.GRAY)
        )));
        inventory.setItem(29, CrownsAPI.getSuiteGui().info(Material.EMERALD, "Rewards", NamedTextColor.GREEN, List.of(
                Component.text("Open the rewards page to see what is ready to claim.", NamedTextColor.GRAY)
        )));
        inventory.setItem(31, CrownsAPI.getSuiteGui().info(Material.WRITABLE_BOOK, "Firsts", NamedTextColor.WHITE, List.of(
                Component.text("Special first-discovery moments are recorded globally.", NamedTextColor.GRAY),
                Component.text(this.firstDiscoveryLine(manager.getFirstDiscoveries(eventKey)), NamedTextColor.DARK_GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to the event page.", NamedTextColor.GRAY)
        ), "events:view:" + eventKey, "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openPlaceholder(Player player) {
        Inventory inventory = CrownsMenuHolder.create("events-placeholder", 54, Component.text("The Lowlight God's Release", NamedTextColor.DARK_PURPLE));
        inventory.setItem(22, CrownsAPI.getSuiteGui().info(Material.DRAGON_EGG, "Coming Later", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("This event page is intentionally blank for now.", NamedTextColor.GRAY)
        ), "lowlight/suite/lowlight_god"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to event selection.", NamedTextColor.GRAY)
        ), "events:selector", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    private ItemStack eventCard(String eventKey, Material material, NamedTextColor color, String modelPath) {
        EventManager manager = this.plugin.getEventManager();
        boolean live = manager.getActiveEventKey().equalsIgnoreCase(eventKey);
        return CrownsAPI.getSuiteGui().button(material, manager.getMenuLabel(eventKey), color, List.of(
                Component.text(live ? manager.getStatusLabel() : "Archive Page", NamedTextColor.YELLOW),
                Component.text(live ? "Open the live event." : "Browse this event's archive page.", NamedTextColor.GRAY)
        ), "events:view:" + eventKey, modelPath);
    }

    private List<Component> collectorLore(List<CollectorEntry> collectors) {
        List<Component> lore = new ArrayList<>();
        if (collectors.isEmpty()) {
            lore.add(Component.text("No collectors yet.", NamedTextColor.GRAY));
            return lore;
        }
        for (int i = 0; i < collectors.size(); i++) {
            CollectorEntry collector = collectors.get(i);
            lore.add(Component.text((i + 1) + ". " + collector.name() + " - " + collector.relics(), NamedTextColor.GRAY));
        }
        return lore;
    }

    private List<Component> milestoneLore(List<MilestoneStatus> milestones) {
        List<Component> lore = new ArrayList<>();
        for (MilestoneStatus milestone : milestones) {
            lore.add(Component.text(milestone.displayName() + ": " + milestone.progress() + "/" + milestone.target(),
                    milestone.completedAt() > 0L ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        return lore;
    }

    private String firstDiscoveryLine(List<FirstDiscovery> discoveries) {
        return discoveries.stream()
                .filter(discovery -> discovery != null && discovery.playerName() != null)
                .findFirst()
                .map(discovery -> "Earliest highlight: " + discovery.label() + " by " + discovery.playerName())
                .orElse("First-discovery records appear here once players make progress.");
    }
}
