package com.xkstudios.crowns.gui;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.inbox.InboxEntry;
import com.xkstudios.crowns.economy.JobManager;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class MenuManager {
    private final CrownsPlugin plugin;

    public MenuManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-main", 54, Component.text("Crowns Economy", NamedTextColor.GOLD));
        inventory.setItem(10, this.button(Material.GOLD_INGOT, "Wallet", NamedTextColor.GOLD, List.of(
                Component.text("Balance: " + Currency.format(this.plugin.getEconomy().getBalance(player)), NamedTextColor.YELLOW),
                Component.text("Click for your wallet summary.", NamedTextColor.GRAY)
        ), "ce:menu:wallet", "lowlight/economy/wallet"));
        inventory.setItem(12, this.button(Material.CHEST, "Auction House", NamedTextColor.GREEN, List.of(
                Component.text("Bid on timed listings or sell your own.", NamedTextColor.GRAY)
        ), "ce:menu:auction", "lowlight/economy/auction_house"));
        inventory.setItem(14, this.button(Material.CHEST_MINECART, "Market Stalls", NamedTextColor.YELLOW, List.of(
                Component.text("Permanent storefronts with upgrades.", NamedTextColor.GRAY)
        ), "ce:menu:stalls", "lowlight/economy/market_stalls"));
        inventory.setItem(16, this.button(Material.WRITABLE_BOOK, "Jobs", NamedTextColor.AQUA, List.of(
                Component.text("Claim delivery contracts and turn them in.", NamedTextColor.GRAY)
        ), "ce:menu:jobs", "lowlight/economy/jobs"));
        inventory.setItem(28, this.button(Material.HOPPER, "Demand Board", NamedTextColor.GREEN, List.of(
                Component.text("Sell gathered goods to The Server.", NamedTextColor.GRAY)
        ), "ce:menu:demand", "lowlight/economy/demand_board"));
        inventory.setItem(30, this.button(Material.WANDERING_TRADER_SPAWN_EGG, "Server Trader", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Spend money on rotating stock.", NamedTextColor.GRAY)
        ), "ce:menu:trader", "lowlight/economy/server_trader"));
        inventory.setItem(32, this.button(Material.JUKEBOX, "Gambling", NamedTextColor.RED, List.of(
                Component.text("Lottery, coinflip, and slots.", NamedTextColor.GRAY)
        ), "ce:menu:gambling", "lowlight/economy/gambling"));
        inventory.setItem(34, this.button(Material.PAPER, "Inbox", NamedTextColor.YELLOW, List.of(
                Component.text("Unread: " + this.plugin.getInboxManager().getUnreadCount(player.getUniqueId()), NamedTextColor.GRAY)
        ), "ce:menu:inbox", "lowlight/economy/inbox"));
        inventory.setItem(40, this.button(Material.PLAYER_HEAD, "Top Balances", NamedTextColor.WHITE, List.of(
                Component.text("See who is leading the economy.", NamedTextColor.GRAY)
        ), "ce:menu:top", "lowlight/economy/top_balances"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Suite.", NamedTextColor.GRAY)
        ), "ce:menu:suite", "lowlight/suite/nav_back"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openMarketMenu(Player player) {
        this.openMainMenu(player);
    }

    public void openJobsMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-jobs", 54, Component.text("Crowns Jobs", NamedTextColor.GOLD));
        List<JobManager.Job> jobs = this.plugin.getJobManager().getAvailableJobs();
        List<JobManager.Job> claimed = this.plugin.getJobManager().getClaimedJobs(player.getUniqueId());
        int slot = 10;
        for (JobManager.Job job : jobs) {
            if (slot >= 16) {
                break;
            }
            inventory.setItem(slot, this.button(Material.WRITABLE_BOOK, job.description(), NamedTextColor.AQUA, List.of(
                    Component.text("Turn in: " + job.amount() + "x " + job.target(), NamedTextColor.GRAY),
                    Component.text("Reward: " + Currency.format(job.reward()), NamedTextColor.YELLOW),
                    Component.text("Click to accept.", NamedTextColor.GREEN)
            ), "ce:jobs:accept:" + job.id()));
            slot++;
        }
        slot = 28;
        for (JobManager.Job job : claimed) {
            if (slot >= 34) {
                break;
            }
            inventory.setItem(slot, this.button(Material.BOOK, job.description(), NamedTextColor.GREEN, List.of(
                    Component.text("Owned contract", NamedTextColor.GRAY),
                    Component.text("Turn in: " + job.amount() + "x " + job.target(), NamedTextColor.GRAY),
                    Component.text("Reward: " + Currency.format(job.reward()), NamedTextColor.YELLOW),
                    Component.text("Click when ready to complete.", NamedTextColor.AQUA)
            ), "ce:jobs:complete:" + job.id()));
            slot++;
        }
        inventory.setItem(19, this.info(Material.PAPER, "Open Contracts", List.of(Component.text("Available jobs you can claim.", NamedTextColor.GRAY))));
        inventory.setItem(37, this.info(Material.PAPER, "Your Contracts", List.of(Component.text("Claimed jobs waiting on materials.", NamedTextColor.GRAY))));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openInboxMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-inbox", 54, Component.text("Crowns Inbox", NamedTextColor.GOLD));
        List<InboxEntry> entries = this.plugin.getInboxManager().getEntries(player.getUniqueId(), 28);
        int slot = 10;
        for (InboxEntry entry : entries) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(Material.PAPER, entry.title(),
                    entry.unread() ? NamedTextColor.YELLOW : NamedTextColor.WHITE,
                    List.of(
                            Component.text(entry.body() == null ? "No details." : entry.body(), NamedTextColor.GRAY),
                            Component.text(entry.unread() ? "Click to mark read." : "Already read.", NamedTextColor.AQUA)
                    ),
                    "ce:inbox:read:" + entry.id()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(48, this.button(Material.LIME_DYE, "Mark All Read", NamedTextColor.GREEN, List.of(), "ce:inbox:readall"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGamblingMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-gambling", 54, Component.text("Crowns Gambling", NamedTextColor.RED));
        int round = this.plugin.getLotteryManager().getCurrentRound();
        inventory.setItem(11, this.button(Material.SUNFLOWER, "Lottery", NamedTextColor.GOLD, List.of(
                Component.text("Pot: " + Currency.format(this.plugin.getLotteryManager().getPot(round)), NamedTextColor.GRAY),
                Component.text("Your tickets: " + this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), round), NamedTextColor.GRAY),
                Component.text("Click to buy a ticket.", NamedTextColor.YELLOW)
        ), "ce:gambling:lottery", "lowlight/economy/gambling_lottery"));
        inventory.setItem(13, this.button(Material.GOLD_NUGGET, "Coinflip", NamedTextColor.YELLOW, List.of(
                Component.text("Challenge another player to a 50/50 bet.", NamedTextColor.GRAY),
                Component.text("Accept or deny pending flips from here.", NamedTextColor.GRAY)
        ), "ce:gambling:coinflip", "lowlight/economy/gambling_coinflip"));
        inventory.setItem(15, this.button(Material.JUKEBOX, "Slots", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Spin a solo slots machine for Crowns.", NamedTextColor.GRAY),
                Component.text("Three of a kind pays out.", NamedTextColor.GRAY)
        ), "ce:gambling:slots", "lowlight/economy/gambling_slots"));
        inventory.setItem(22, this.info(Material.PAPER, "Quick Notes", List.of(
                Component.text("Lottery is server-wide and auto-draws on a timer.", NamedTextColor.GRAY),
                Component.text("Coinflip is direct player-vs-player.", NamedTextColor.GRAY),
                Component.text("Slots are instant and solo.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open", "lowlight/suite/nav_back"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openLotteryMenu(Player player) {
        int round = this.plugin.getLotteryManager().getCurrentRound();
        Inventory inventory = CrownsMenuHolder.create("ce-lottery", 54, Component.text("Crowns Lottery", NamedTextColor.GOLD));
        inventory.setItem(13, this.button(Material.SUNFLOWER, "Buy Ticket", NamedTextColor.GOLD, List.of(
                Component.text("Ticket price: " + Currency.format(this.plugin.getConfig().getLong("lottery.ticket-price", 100L)), NamedTextColor.GRAY),
                Component.text("Your tickets: " + this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), round), NamedTextColor.GRAY),
                Component.text("Current pot: " + Currency.format(this.plugin.getLotteryManager().getPot(round)), NamedTextColor.YELLOW)
        ), "ce:gambling:lotterybuy", "lowlight/economy/gambling_lottery"));
        inventory.setItem(22, this.info(Material.PAPER, "Round Status", List.of(
                Component.text("Round: #" + round, NamedTextColor.GRAY),
                Component.text("Max tickets/player: " + this.plugin.getConfig().getInt("lottery.max-tickets-per-player", 10), NamedTextColor.GRAY),
                Component.text("Auto-draw runs on the configured schedule.", NamedTextColor.GRAY)
        )));
        inventory.setItem(31, this.info(Material.BOOK, "How It Works", List.of(
                Component.text("Buy tickets for the live round.", NamedTextColor.GRAY),
                Component.text("One winner gets the pot minus the house cut.", NamedTextColor.GRAY)
        )));
        if (player.hasPermission("crowns.admin") || player.isOp()) {
            inventory.setItem(40, this.button(Material.REDSTONE_TORCH, "Draw Now", NamedTextColor.RED, List.of(
                    Component.text("Staff-only forced draw.", NamedTextColor.GRAY)
            ), "ce:gambling:lotterydraw"));
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:gambling", "lowlight/suite/nav_back"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openCoinflipMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-coinflip", 54, Component.text("Crowns Coinflip", NamedTextColor.YELLOW));
        inventory.setItem(11, this.button(Material.GOLD_NUGGET, "Start Challenge", NamedTextColor.YELLOW, List.of(
                Component.text("Use /ce coinflip <player> <amount>.", NamedTextColor.GRAY),
                Component.text("Challenge another player to a 50/50 wager.", NamedTextColor.GRAY)
        ), "ce:gambling:coinflipprompt", "lowlight/economy/gambling_coinflip"));
        inventory.setItem(13, this.button(Material.LIME_DYE, "Accept Challenge", NamedTextColor.GREEN, List.of(
                Component.text("Accept the current incoming coinflip.", NamedTextColor.GRAY)
        ), "ce:gambling:coinflipaccept"));
        inventory.setItem(15, this.button(Material.RED_DYE, "Deny Challenge", NamedTextColor.RED, List.of(
                Component.text("Decline the current incoming coinflip.", NamedTextColor.GRAY)
        ), "ce:gambling:coinflipdeny"));
        inventory.setItem(22, this.info(Material.PAPER, "Quick Rules", List.of(
                Component.text("The challenger and accepter both pay the stake.", NamedTextColor.GRAY),
                Component.text("Winner takes the full doubled pot.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:gambling", "lowlight/suite/nav_back"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action) {
        return this.button(material, name, color, lore, action, null);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action, String modelPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        List<Component> fullLore = new ArrayList<>(lore);
        fullLore.add(Component.text(action, NamedTextColor.DARK_GRAY));
        meta.lore(fullLore);
        PackModelHelper.apply(meta, modelPath);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack info(Material material, String name, List<Component> lore) {
        return this.button(material, name, NamedTextColor.WHITE, lore, "ce:none");
    }

    private void fillBorder(Inventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null && (i < 9 || i >= inventory.getSize() - 9 || i % 9 == 0 || i % 9 == 8)) {
                inventory.setItem(i, this.info(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
            }
        }
    }
}
