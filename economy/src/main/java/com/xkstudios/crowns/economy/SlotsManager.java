package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class SlotsManager {
    private static final Material[] REELS = {
            Material.EMERALD,
            Material.DIAMOND,
            Material.GOLD_INGOT,
            Material.AMETHYST_SHARD,
            Material.NETHER_STAR
    };

    private final CrownsPlugin plugin;
    private final Random random = new Random();

    public SlotsManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-slots", 54, Component.text("Crowns Slots", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(10, CrownsAPI.getSuiteGui().button(Material.IRON_NUGGET, "Spin Small", NamedTextColor.WHITE, List.of(
                Component.text("Cost: " + Currency.format(this.getSmallCost()), NamedTextColor.GRAY),
                Component.text("Low-risk starter spin.", NamedTextColor.GRAY)
        ), "ce:slots:spin:small", "lowlight/economy/slots_small"));
        inventory.setItem(13, CrownsAPI.getSuiteGui().button(Material.GOLD_INGOT, "Spin Standard", NamedTextColor.GOLD, List.of(
                Component.text("Cost: " + Currency.format(this.getStandardCost()), NamedTextColor.GRAY),
                Component.text("Balanced payout table.", NamedTextColor.GRAY)
        ), "ce:slots:spin:standard", "lowlight/economy/slots_standard"));
        inventory.setItem(16, CrownsAPI.getSuiteGui().button(Material.DIAMOND, "Spin High Roller", NamedTextColor.AQUA, List.of(
                Component.text("Cost: " + Currency.format(this.getHighCost()), NamedTextColor.GRAY),
                Component.text("Big swings, best jackpot.", NamedTextColor.GRAY)
        ), "ce:slots:spin:high", "lowlight/economy/slots_high"));
        inventory.setItem(22, CrownsAPI.getSuiteGui().info(Material.BOOK, "How It Works", NamedTextColor.YELLOW, List.of(
                Component.text("Three matching symbols pay out.", NamedTextColor.GRAY),
                Component.text("Two matching symbols refund part of your stake.", NamedTextColor.GRAY),
                Component.text("Jackpot symbol: Nether Star.", NamedTextColor.GRAY)
        ), "lowlight/economy/slots_rules"));
        inventory.setItem(31, CrownsAPI.getSuiteGui().info(Material.EMERALD, "Possible Symbols", NamedTextColor.GREEN, this.symbolLore(), "lowlight/economy/slots_symbols"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Economy.", NamedTextColor.GRAY)
        ), "ce:menu:open", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public String spin(Player player, String tier) {
        long cost = this.costForTier(tier);
        if (cost <= 0L) {
            return "That slots tier does not exist.";
        }
        if (!this.plugin.getEconomy().withdraw(player, cost, "slots-spins", "Slots spin (" + tier + ")")) {
            return "You do not have enough Crowns for that spin.";
        }

        Material first = REELS[this.random.nextInt(REELS.length)];
        Material second = REELS[this.random.nextInt(REELS.length)];
        Material third = REELS[this.random.nextInt(REELS.length)];

        long payout = this.payout(cost, first, second, third);
        if (payout > 0L) {
            this.plugin.getEconomy().deposit(player, payout, "slots-payouts", "Slots win (" + tier + ")");
            if (CrownsAPI.getInbox() != null && payout >= cost * 4L) {
                CrownsAPI.getInbox().sendNotification(player.getUniqueId(), "Slots Win", "You won " + Currency.format(payout) + " at the Crowns slots.");
            }
        } else {
            this.plugin.getEconomyLedgerManager().recordServerSink("slots-house", cost, player.getName() + " lost a slots spin.");
        }

        return "Slots: " + this.pretty(first) + " | " + this.pretty(second) + " | " + this.pretty(third)
                + (payout > 0L ? " -> won " + Currency.format(payout) : " -> no payout");
    }

    private long payout(long cost, Material first, Material second, Material third) {
        boolean triple = first == second && second == third;
        boolean pair = first == second || second == third || first == third;
        if (triple) {
            if (first == Material.NETHER_STAR) {
                return cost * 10L;
            }
            if (first == Material.DIAMOND) {
                return cost * 6L;
            }
            if (first == Material.EMERALD) {
                return cost * 5L;
            }
            return cost * 4L;
        }
        if (pair) {
            if (first == Material.NETHER_STAR || second == Material.NETHER_STAR || third == Material.NETHER_STAR) {
                return Math.round(cost * 1.5D);
            }
            return Math.round(cost * 0.6D);
        }
        return 0L;
    }

    private long costForTier(String tier) {
        return switch (tier.toLowerCase()) {
            case "small" -> this.getSmallCost();
            case "standard" -> this.getStandardCost();
            case "high" -> this.getHighCost();
            default -> 0L;
        };
    }

    private long getSmallCost() {
        return Math.max(10L, this.plugin.getConfig().getLong("slots.small-cost", 100L));
    }

    private long getStandardCost() {
        return Math.max(10L, this.plugin.getConfig().getLong("slots.standard-cost", 250L));
    }

    private long getHighCost() {
        return Math.max(10L, this.plugin.getConfig().getLong("slots.high-cost", 500L));
    }

    private String pretty(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private List<Component> symbolLore() {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Emeralds, Diamonds, Gold, Amethyst, Nether Stars", NamedTextColor.GRAY));
        lore.add(Component.text("Triple stars are the jackpot.", NamedTextColor.YELLOW));
        return lore;
    }
}
