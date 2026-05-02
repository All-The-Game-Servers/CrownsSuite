package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.data.PlayerData;
import com.xkstudios.crowns.economy.Currency;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class EconomyCommand implements CommandExecutor, TabCompleter {
    private final CrownsPlugin plugin;

    public EconomyCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            CrownsAPI.openSuiteHome(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "bal", "balance" -> this.cmdBalance(player, args);
            case "pay" -> this.cmdPay(player, args);
            case "top" -> this.cmdTop(player);
            case "auction", "ah" -> this.cmdAuction(player, args);
            case "stalls", "stall" -> this.cmdStalls(player, args);
            case "jobs" -> this.cmdJobs(player, args);
            case "lottery", "lotto" -> this.cmdLottery(player, args);
            case "coinflip", "cf" -> this.cmdCoinflip(player, args);
            case "slots" -> this.cmdSlots(player, args);
            case "trader" -> this.plugin.getDemandManager().openTraderMenu(player);
            case "demand" -> this.plugin.getDemandManager().openDemandMenu(player);
            case "commissions", "commission" -> this.cmdCommissions(player, args);
            case "contracts", "contract" -> this.cmdContracts(player, args);
            case "inbox" -> this.plugin.getMenuManager().openInboxMenu(player);
            case "status" -> {
                if (CrownsAPI.getSuiteGui() != null) {
                    CrownsAPI.getSuiteGui().openStatus(player);
                } else {
                    this.msg(player, "Crowns Suite status is unavailable.", NamedTextColor.RED);
                }
            }
            case "help" -> this.sendHelp(player);
            default -> this.sendHelp(player);
        }
        return true;
    }

    private void cmdBalance(Player player, String[] args) {
        if (args.length > 1) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                this.msg(player, "Player not found.", NamedTextColor.RED);
                return;
            }
            this.msg(player, target.getName() + ": " + Currency.format(this.plugin.getEconomy().getBalance(target)), NamedTextColor.GREEN);
        } else {
            this.msg(player, "Balance: " + Currency.format(this.plugin.getEconomy().getBalance(player)), NamedTextColor.GREEN);
        }
    }

    private void cmdPay(Player player, String[] args) {
        if (args.length < 3) {
            this.msg(player, "Usage: /ce pay <player> <amount>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || target.equals(player)) {
            this.msg(player, "Invalid player.", NamedTextColor.RED);
            return;
        }
        try {
            long amount = Currency.parse(args[2]);
            if (this.plugin.getEconomy().transfer(player, target, amount)) {
                this.msg(player, "Paid " + Currency.format(amount) + " to " + target.getName(), NamedTextColor.GREEN);
            } else {
                this.msg(player, "Insufficient funds.", NamedTextColor.RED);
            }
        } catch (NumberFormatException exception) {
            this.msg(player, "Invalid amount.", NamedTextColor.RED);
        }
    }

    private void cmdTop(Player player) {
        List<PlayerData> top = this.plugin.getDataManager().getTopBalances(10);
        player.sendMessage(Component.text("=== Richest Players ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (int i = 0; i < top.size(); i++) {
            PlayerData entry = top.get(i);
            player.sendMessage(Component.text((i + 1) + ". " + entry.getName() + " - " + Currency.format(entry.getBalance()), NamedTextColor.GRAY));
        }
    }

    private void cmdAuction(Player player, String[] args) {
        if (args.length == 1) {
            this.plugin.getAuctionManager().openMainMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("sell")) {
            if (args.length < 3) {
                this.msg(player, "Usage: /ce auction sell <price> [hours]", NamedTextColor.RED);
                return;
            }
            try {
                long price = Currency.parse(args[2]);
                int hours = args.length >= 4 ? Integer.parseInt(args[3]) : this.plugin.getAuctionManager().getDefaultDurationHours();
                if (!this.plugin.getAuctionManager().isDurationAllowed(hours)) {
                    this.msg(player, "Duration must be between " + this.plugin.getAuctionManager().getMinDurationHours() + " and " + this.plugin.getAuctionManager().getMaxDurationHours() + " hours.", NamedTextColor.RED);
                    return;
                }
                if (this.plugin.getAuctionManager().createListing(player, player.getInventory().getItemInMainHand(), price, hours)) {
                    this.msg(player, "Auction created.", NamedTextColor.GREEN);
                } else {
                    this.msg(player, "Could not create that listing.", NamedTextColor.RED);
                }
            } catch (NumberFormatException exception) {
                this.msg(player, "Invalid price or duration.", NamedTextColor.RED);
            }
            return;
        }
        this.plugin.getAuctionManager().openMainMenu(player);
    }

    private void cmdStalls(Player player, String[] args) {
        if (args.length == 1) {
            this.plugin.getStallManager().openHub(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "buy", "unlock" -> {
                boolean bought = this.plugin.getStallManager().purchaseStall(player);
                this.msg(player, bought ? "Permanent stall unlocked." : "Could not unlock a stall.", bought ? NamedTextColor.GREEN : NamedTextColor.RED);
            }
            case "my", "mine" -> this.plugin.getStallManager().openMyStall(player);
            case "browse" -> this.plugin.getStallManager().openBrowse(player, 0, "*");
            case "upgrades" -> this.plugin.getStallManager().openUpgrades(player);
            default -> this.plugin.getStallManager().openHub(player);
        }
    }

    private void cmdJobs(Player player, String[] args) {
        if (args.length == 1) {
            this.plugin.getMenuManager().openJobsMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("list")) {
            player.sendMessage(Component.text("=== Current Jobs ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            for (var job : this.plugin.getJobManager().getAvailableJobs()) {
                player.sendMessage(Component.text("#" + job.id() + " " + job.description() + " - " + Currency.format(job.reward()), NamedTextColor.GRAY));
            }
            return;
        }
        if (args[1].equalsIgnoreCase("complete") && args.length >= 3) {
            try {
                int jobId = Integer.parseInt(args[2]);
                boolean complete = this.plugin.getJobManager().completeJob(player, jobId);
                this.msg(player, complete ? "Job completed." : "You can't complete that job yet.", complete ? NamedTextColor.GREEN : NamedTextColor.RED);
            } catch (NumberFormatException exception) {
                this.msg(player, "Invalid job id.", NamedTextColor.RED);
            }
            return;
        }
        this.plugin.getMenuManager().openJobsMenu(player);
    }

    private void cmdLottery(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("buy")) {
            long price = this.plugin.getConfig().getLong("lottery.ticket-price", 100L);
            if (this.plugin.getLotteryManager().buyTicket(player)) {
                int tickets = this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), this.plugin.getLotteryManager().getCurrentRound());
                this.msg(player, "Lottery ticket purchased for " + Currency.format(price) + ". You now hold " + tickets + " ticket(s).", NamedTextColor.GREEN);
            } else {
                this.msg(player, "Could not buy a ticket. You may be at the limit or short on Crowns.", NamedTextColor.RED);
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("draw") && player.hasPermission("crowns.admin")) {
            this.plugin.getLotteryManager().drawWinner();
            this.msg(player, "Lottery draw completed.", NamedTextColor.GREEN);
            return;
        }
        int round = this.plugin.getLotteryManager().getCurrentRound();
        player.sendMessage(Component.text("=== Lottery Round #" + round + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Pot: " + Currency.format(this.plugin.getLotteryManager().getPot(round)), NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Your tickets: " + this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), round), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Buy a ticket with /ce lottery buy or through the Gambling menu.", NamedTextColor.AQUA));
    }

    private void cmdCoinflip(Player player, String[] args) {
        if (args.length < 2) {
            this.msg(player, "Usage: /ce coinflip <player> <amount> | /ce coinflip accept | /ce coinflip deny", NamedTextColor.GRAY);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "accept" -> {
                if (!this.plugin.getCoinflipManager().accept(player)) {
                    this.msg(player, "No pending coinflip challenge for you.", NamedTextColor.RED);
                }
            }
            case "deny", "decline" -> {
                if (this.plugin.getCoinflipManager().deny(player)) {
                    this.msg(player, "Coinflip declined.", NamedTextColor.YELLOW);
                } else {
                    this.msg(player, "No pending coinflip challenge to decline.", NamedTextColor.RED);
                }
            }
            default -> {
                if (args.length < 3) {
                    this.msg(player, "Usage: /ce coinflip <player> <amount>", NamedTextColor.RED);
                    return;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || target.equals(player)) {
                    this.msg(player, "Invalid player.", NamedTextColor.RED);
                    return;
                }
                try {
                    long amount = Currency.parse(args[2]);
                    if (this.plugin.getCoinflipManager().challenge(player, target, amount)) {
                        this.msg(player, "Coinflip challenge sent to " + target.getName() + " for " + Currency.format(amount) + ".", NamedTextColor.GOLD);
                    } else {
                        this.msg(player, "Could not send challenge. It may already be pending or unaffordable.", NamedTextColor.RED);
                    }
                } catch (NumberFormatException exception) {
                    this.msg(player, "Invalid amount.", NamedTextColor.RED);
                }
            }
        }
    }

    private void cmdSlots(Player player, String[] args) {
        if (args.length >= 2 && List.of("small", "standard", "high").contains(args[1].toLowerCase())) {
            this.msg(player, this.plugin.getSlotsManager().spin(player, args[1].toLowerCase()), NamedTextColor.LIGHT_PURPLE);
            return;
        }
        this.plugin.getSlotsManager().openMenu(player);
    }

    private void cmdCommissions(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("post")) {
            if (args.length < 4) {
                this.msg(player, "Usage: /ce commissions post <amount> <total payout>", NamedTextColor.RED);
                return;
            }
            try {
                int amount = Integer.parseInt(args[2]);
                long payout = Currency.parse(args[3]);
                boolean created = this.plugin.getContractManager().createCommissionFromHand(player, amount, payout);
                this.msg(player, created ? "Commission posted." : "Could not post that commission. Hold the item you want and check your balance.", created ? NamedTextColor.GREEN : NamedTextColor.RED);
            } catch (NumberFormatException exception) {
                this.msg(player, "Invalid amount or payout.", NamedTextColor.RED);
            }
            return;
        }
        this.plugin.getContractManager().openCommissionsMenu(player);
    }

    private void cmdContracts(Player player, String[] args) {
        this.plugin.getContractManager().openContractsMenu(player);
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("=== CrownsEconomy ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (String line : List.of(
                "/ce - Open the Crowns Suite menu",
                "/ce bal [player] - Check balance",
                "/ce pay <player> <amount> - Send money",
                "/ce top - Economy leaderboard",
                "/ce auction - Open the auction house",
                "/ce auction sell <price> [hours] - List held item",
                "/ce stalls - Open market stalls",
                "/ce jobs - Browse jobs",
                "/ce lottery - View the lottery",
                "/ce lottery buy - Buy a lottery ticket",
                "/ce coinflip <player> <amount> - Send a challenge",
                "/ce coinflip accept - Accept a challenge",
                "/ce slots - Open the slots machine",
                "/ce jobs complete <id> - Finish a delivery contract",
                "/ce demand - Open the server demand board",
                "/ce commissions - Open player commissions",
                "/ce commissions post <amount> <payout> - Post a buy order from your held item",
                "/ce contracts - Open server contracts",
                "/ce trader - Open The Server trader",
                "/ce inbox - Open your stored notifications",
                "/ce status - Open Crowns Suite module status")) {
            player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private void msg(Player player, String message, NamedTextColor color) {
        player.sendMessage(Component.text(message, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("menu", "bal", "pay", "top", "auction", "stalls", "jobs", "lottery", "coinflip", "slots", "demand", "commissions", "contracts", "trader", "inbox", "status", "help")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pay")) {
            return this.onlinePlayerNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stalls")) {
            return List.of("buy", "my", "browse", "upgrades").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("jobs")) {
            return List.of("list", "complete").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lottery")) {
            List<String> options = new ArrayList<>(List.of("buy"));
            if (sender instanceof Player player && player.hasPermission("crowns.admin")) {
                options.add("draw");
            }
            return options.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("coinflip") || args[0].equalsIgnoreCase("cf"))) {
            List<String> options = new ArrayList<>(List.of("accept", "deny"));
            options.addAll(this.onlinePlayerNames(args[1]));
            return options.stream().distinct().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slots")) {
            return List.of("small", "standard", "high").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("commissions") || args[0].equalsIgnoreCase("commission"))) {
            return List.of("post").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    private List<String> onlinePlayerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
