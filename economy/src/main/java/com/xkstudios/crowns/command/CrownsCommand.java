/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.block.Block
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 */
package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.analytics.EconomyLedgerSummary;
import com.xkstudios.crowns.analytics.PlaytimeEntry;
import com.xkstudios.crowns.analytics.PlaytimePeriod;
import com.xkstudios.crowns.analytics.PlaytimeSnapshot;
import com.xkstudios.crowns.data.PlayerData;
import com.xkstudios.crowns.economy.BountyManager;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.economy.JobManager;
import com.xkstudios.crowns.listener.PlayerListener;
import com.xkstudios.crowns.market.ChestShopData;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class CrownsCommand
implements CommandExecutor,
TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private final CrownsPlugin plugin;

    public CrownsCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Player player = (Player)sender;
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            this.plugin.getMenuManager().openMainMenu(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "balance": 
            case "bal": {
                this.cmdBalance(player, args);
                break;
            }
            case "pay": {
                this.cmdPay(player, args);
                break;
            }
            case "top": {
                this.cmdTop(player);
                break;
            }
            case "shop": {
                this.cmdShop(player, args);
                break;
            }
            case "auction": 
            case "ah": {
                this.cmdAuction(player, args);
                break;
            }
            case "stalls":
            case "stall": {
                this.cmdStalls(player, args);
                break;
            }
            case "bounty": {
                this.cmdBounty(player, args);
                break;
            }
            case "jobs": {
                this.cmdJobs(player, args);
                break;
            }
            case "coinflip": 
            case "cf": {
                this.cmdCoinflip(player, args);
                break;
            }
            case "lottery": 
            case "lotto": {
                this.cmdLottery(player, args);
                break;
            }
            case "analytics": {
                this.cmdAnalytics(player, args);
                break;
            }
            case "event": {
                this.cmdEvent(player, args);
                break;
            }
            case "relics": {
                this.cmdEvent(player, new String[]{"event", "board"});
                break;
            }
            case "mod": {
                this.cmdMod(player, args);
                break;
            }
            case "report": {
                this.cmdReport(player, args);
                break;
            }
            case "afk": {
                this.cmdAfk(player);
                break;
            }
            case "inbox": {
                this.cmdInbox(player);
                break;
            }
            case "invsee": {
                this.cmdInvsee(player, args);
                break;
            }
            case "echest": 
            case "ec": {
                this.cmdEnderchest(player, args);
                break;
            }
            case "clearitems": {
                this.cmdClearItems(player, args);
                break;
            }
            case "clearmobs": {
                this.cmdClearMobs(player, args);
                break;
            }
            case "entities": {
                this.cmdEntities(player);
                break;
            }
            case "admin": {
                this.cmdAdmin(player, args);
                break;
            }
            case "help": {
                this.sendHelp(player);
                break;
            }
            default: {
                this.sendHelp(player);
            }
        }
        return true;
    }

    private void cmdBalance(Player player, String[] args) {
        if (args.length > 1) {
            Player target = Bukkit.getPlayer((String)args[1]);
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
        Player target = Bukkit.getPlayer((String)args[1]);
        if (target == null || target.equals((Object)player)) {
            this.msg(player, "Invalid player.", NamedTextColor.RED);
            return;
        }
        try {
            long amount = Currency.parse(args[2]);
            double taxRate = this.plugin.getConfig().getDouble("economy.taxes.transaction-tax", 0.03);
            long tax = (long)((double)amount * taxRate);
            if (this.plugin.getEconomy().transfer(player, target, amount)) {
                this.msg(player, "Paid " + Currency.format(amount) + " to " + target.getName() + " (tax: " + Currency.format(tax) + ")", NamedTextColor.GREEN);
                this.msg(target, "Received " + Currency.format(amount) + " from " + player.getName(), NamedTextColor.GREEN);
            } else {
                this.msg(player, "Insufficient funds. Need " + Currency.format(amount + tax), NamedTextColor.RED);
            }
        }
        catch (NumberFormatException e) {
            this.msg(player, "Invalid amount. Use: 500, 5s, 1c, 2c50s10p", NamedTextColor.RED);
        }
    }

    private void cmdTop(Player player) {
        List<PlayerData> top = this.plugin.getDataManager().getTopBalances(10);
        player.sendMessage((Component)Component.text((String)"\u2550\u2550\u2550 Richest Players \u2550\u2550\u2550", (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
        for (int i = 0; i < top.size(); ++i) {
            PlayerData pd = top.get(i);
            NamedTextColor c = i == 0 ? NamedTextColor.GOLD : (i == 1 ? NamedTextColor.GRAY : (i == 2 ? NamedTextColor.RED : NamedTextColor.WHITE));
            player.sendMessage((Component)Component.text((String)("  " + (i + 1) + ". " + pd.getName() + " \u2014 " + Currency.format(pd.getBalance())), (TextColor)c));
        }
    }

    private void cmdShop(Player player, String[] args) {
        if (args.length < 2) {
            this.msg(player, "Usage: /ce shop create <price> | /ce shop remove | /ce shop list", NamedTextColor.GRAY);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create": {
                if (args.length < 3) {
                    this.msg(player, "Usage: /ce shop create <price>", NamedTextColor.RED);
                    return;
                }
                Block target = player.getTargetBlockExact(5);
                if (target == null) {
                    this.msg(player, "Look at a chest.", NamedTextColor.RED);
                    return;
                }
                try {
                    long price = Currency.parse(args[2]);
                    long fee = this.plugin.getConfig().getLong("economy.taxes.shop-creation-fee", 200L);
                    if (this.plugin.getShopManager().create(player, target, price)) {
                        this.msg(player, "Shop created! Price: " + Currency.format(price) + " (fee: " + Currency.format(fee) + ")", NamedTextColor.GREEN);
                        break;
                    }
                    this.msg(player, "Failed. Chest with items? Limit reached? Enough funds for fee?", NamedTextColor.RED);
                }
                catch (NumberFormatException e) {
                    this.msg(player, "Invalid price.", NamedTextColor.RED);
                }
                break;
            }
            case "remove": {
                Block target = player.getTargetBlockExact(5);
                if (target == null) {
                    this.msg(player, "Look at a shop chest.", NamedTextColor.RED);
                    return;
                }
                String key = target.getWorld().getName() + ":" + target.getX() + ":" + target.getY() + ":" + target.getZ();
                ChestShopData shop = this.plugin.getShopManager().getAt(key);
                if (shop == null || !shop.getOwner().equals(player.getUniqueId())) {
                    this.msg(player, "Not your shop.", NamedTextColor.RED);
                    return;
                }
                this.plugin.getShopManager().remove(key);
                this.msg(player, "Shop removed.", NamedTextColor.YELLOW);
                break;
            }
            case "list": {
                List<ChestShopData> shops = this.plugin.getShopManager().getShopsByOwner(player.getUniqueId());
                if (shops.isEmpty()) {
                    this.msg(player, "You have no shops.", NamedTextColor.GRAY);
                    return;
                }
                player.sendMessage((Component)Component.text((String)"Your shops:", (TextColor)NamedTextColor.GOLD));
                for (ChestShopData s : shops) {
                    player.sendMessage((Component)Component.text((String)("  " + s.getWorld() + " " + s.getX() + "," + s.getY() + "," + s.getZ() + " \u2014 " + Currency.format(s.getPrice())), (TextColor)NamedTextColor.GRAY));
                }
                break;
            }
            default: {
                this.msg(player, "Usage: /ce shop create <price> | remove | list", NamedTextColor.GRAY);
            }
        }
    }

    private void cmdAuction(Player player, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("sell")) {
            try {
                long price = Currency.parse(args[2]);
                int hours = args.length >= 4 ? Integer.parseInt(args[3]) : this.plugin.getAuctionManager().getDefaultDurationHours();
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType() == Material.AIR) {
                    this.msg(player, "Hold an item to sell.", NamedTextColor.RED);
                    return;
                }
                long fee = this.plugin.getConfig().getLong("economy.taxes.auction-listing-fee", 100L);
                if (this.plugin.getAuctionManager().createListing(player, held, price, hours)) {
                    this.msg(player, "Listed for " + Currency.format(price) + " for " + hours + "h (fee: " + Currency.format(fee) + ")", NamedTextColor.GREEN);
                } else {
                    this.msg(player, "Failed. Check limits, duration range, price range, or balance.", NamedTextColor.RED);
                }
            }
            catch (NumberFormatException e) {
                this.msg(player, "Usage: /ce auction sell <price> [hours]", NamedTextColor.RED);
            }
            return;
        }
        this.plugin.getAuctionManager().openMainMenu(player);
    }

    private void cmdStalls(Player player, String[] args) {
        if (!player.hasPermission("crowns.stalls")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (!this.plugin.getStallManager().isEnabled()) {
            this.msg(player, "Market stalls are disabled.", NamedTextColor.RED);
            return;
        }
        if (args.length == 1) {
            this.plugin.getStallManager().openHub(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "rent" -> {
                if (this.plugin.getStallManager().rentStall(player)) {
                    this.msg(player, "Market stall rented.", NamedTextColor.GREEN);
                    this.plugin.getStallManager().openMyStalls(player);
                } else {
                    this.msg(player, "Could not rent a stall. Check your balance or stall limit.", NamedTextColor.RED);
                }
            }
            case "my" -> this.plugin.getStallManager().openMyStalls(player);
            case "browse" -> {
                String owner = args.length >= 3 ? args[2] : "*";
                this.plugin.getStallManager().openBrowse(player, 0, "*", owner);
            }
            case "reclaim" -> this.plugin.getStallManager().openOverflow(player);
            case "admin" -> {
                if (!player.hasPermission("crowns.stalls.admin")) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                this.plugin.getStallManager().openAdminMenu(player);
            }
            default -> this.msg(player, "Usage: /ce stalls | rent | my | browse [owner] | reclaim", NamedTextColor.GRAY);
        }
    }

    private void cmdBounty(Player player, String[] args) {
        if (args.length < 2) {
            this.msg(player, "Usage: /ce bounty <player> <amount> | /ce bounty list", NamedTextColor.GRAY);
            return;
        }
        if (args[1].equalsIgnoreCase("list")) {
            List<BountyManager.Bounty> bounties = this.plugin.getBountyManager().getActiveBounties();
            if (bounties.isEmpty()) {
                this.msg(player, "No active bounties.", NamedTextColor.GRAY);
                return;
            }
            player.sendMessage((Component)Component.text((String)"\u2550\u2550\u2550 Bounty Board \u2550\u2550\u2550", (TextColor)NamedTextColor.RED, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
            for (BountyManager.Bounty b : bounties) {
                String targetName = Bukkit.getOfflinePlayer((UUID)b.target).getName();
                player.sendMessage((Component)Component.text((String)("  " + (targetName != null ? targetName : "?") + " \u2014 " + Currency.format(b.amount)), (TextColor)NamedTextColor.YELLOW));
            }
            return;
        }
        if (args.length < 3) {
            this.msg(player, "Usage: /ce bounty <player> <amount>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayer((String)args[1]);
        if (target == null || target.equals((Object)player)) {
            this.msg(player, "Invalid player.", NamedTextColor.RED);
            return;
        }
        try {
            long amount = Currency.parse(args[2]);
            double feeRate = this.plugin.getConfig().getDouble("economy.taxes.bounty-fee", 0.1);
            long fee = (long)((double)amount * feeRate);
            if (this.plugin.getBountyManager().postBounty(player.getUniqueId(), target.getUniqueId(), amount)) {
                this.msg(player, "Bounty placed on " + target.getName() + ": " + Currency.format(amount) + " (fee: " + Currency.format(fee) + ")", NamedTextColor.RED);
                Bukkit.broadcast((Component)Component.text((String)("\u2620 Bounty: " + Currency.format(amount) + " on " + target.getName() + "!"), (TextColor)NamedTextColor.RED));
            } else {
                this.msg(player, "Failed. Check balance, minimum, or active bounty limit.", NamedTextColor.RED);
            }
        }
        catch (NumberFormatException e) {
            this.msg(player, "Invalid amount.", NamedTextColor.RED);
        }
    }

    private void cmdJobs(Player player, String[] args) {
        List<JobManager.Job> jobs = this.plugin.getJobManager().getAvailableJobs();
        if (jobs.isEmpty()) {
            this.msg(player, "No jobs available right now. Check back later!", NamedTextColor.GRAY);
            return;
        }
        player.sendMessage((Component)Component.text((String)"\u2550\u2550\u2550 Jobs Board \u2550\u2550\u2550", (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
        for (JobManager.Job j : jobs) {
            player.sendMessage((Component)Component.text((String)("  [" + j.id + "] " + j.description + " (" + j.amount + ") \u2014 " + Currency.format(j.reward)), (TextColor)NamedTextColor.AQUA));
        }
        this.msg(player, "Accept a job: /ce jobs accept <id>", NamedTextColor.GRAY);
        if (args.length >= 3 && args[1].equalsIgnoreCase("accept")) {
            try {
                int id = Integer.parseInt(args[2]);
                if (this.plugin.getJobManager().claimJob(id, player.getUniqueId())) {
                    JobManager.Job job = this.plugin.getJobManager().getJob(id);
                    if (job != null) {
                        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "job_accepted",
                                "Job accepted",
                                job.description + " for " + Currency.format(job.reward) + ".");
                    }
                    this.msg(player, "Job accepted! Complete the task to earn the reward.", NamedTextColor.GREEN);
                } else {
                    this.msg(player, "Couldn't claim that job.", NamedTextColor.RED);
                }
            }
            catch (NumberFormatException e) {
                this.msg(player, "Invalid job ID.", NamedTextColor.RED);
            }
        }
    }

    private void cmdCoinflip(Player player, String[] args) {
        if (args.length < 2) {
            this.msg(player, "Usage: /ce coinflip <player> <amount> | /ce coinflip accept | /ce coinflip deny", NamedTextColor.GRAY);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "accept": {
                if (this.plugin.getCoinflipManager().accept(player)) break;
                this.msg(player, "No pending coinflip challenge for you.", NamedTextColor.RED);
                break;
            }
            case "deny": 
            case "decline": {
                if (this.plugin.getCoinflipManager().deny(player)) {
                    this.msg(player, "Coinflip declined.", NamedTextColor.YELLOW);
                    break;
                }
                this.msg(player, "No pending challenge to decline.", NamedTextColor.RED);
                break;
            }
            default: {
                if (args.length < 3) {
                    this.msg(player, "Usage: /ce coinflip <player> <amount>", NamedTextColor.RED);
                    return;
                }
                Player target = Bukkit.getPlayer((String)args[1]);
                if (target == null || target.equals((Object)player)) {
                    this.msg(player, "Invalid player.", NamedTextColor.RED);
                    return;
                }
                try {
                    long amount = Currency.parse(args[2]);
                    if (this.plugin.getCoinflipManager().challenge(player, target, amount)) {
                        this.msg(player, "Coinflip challenge sent to " + target.getName() + " for " + Currency.format(amount) + "!", NamedTextColor.GOLD);
                        break;
                    }
                    this.msg(player, "Could not send challenge. Already pending or insufficient funds.", NamedTextColor.RED);
                    break;
                }
                catch (NumberFormatException e) {
                    this.msg(player, "Invalid amount.", NamedTextColor.RED);
                }
            }
        }
    }

    private void cmdLottery(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("buy")) {
            long price = this.plugin.getConfig().getLong("lottery.ticket-price", 100L);
            if (this.plugin.getLotteryManager().buyTicket(player)) {
                int tickets = this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), this.plugin.getLotteryManager().getCurrentRound());
                this.msg(player, "Lottery ticket purchased! (" + Currency.format(price) + ") You have " + tickets + " ticket(s) this round.", NamedTextColor.GREEN);
            } else {
                this.msg(player, "Could not buy ticket. Max tickets reached or insufficient funds.", NamedTextColor.RED);
            }
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("draw") && player.hasPermission("crowns.admin")) {
            this.plugin.getLotteryManager().drawWinner();
            this.msg(player, "Lottery drawn!", NamedTextColor.GREEN);
        } else {
            int round = this.plugin.getLotteryManager().getCurrentRound();
            long pot = this.plugin.getLotteryManager().getPot(round);
            int total = this.plugin.getLotteryManager().getTotalTickets(round);
            int mine = this.plugin.getLotteryManager().getPlayerTickets(player.getUniqueId(), round);
            long price = this.plugin.getConfig().getLong("lottery.ticket-price", 100L);
            player.sendMessage((Component)Component.text((String)("=== Lottery Round #" + round + " ==="), (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
            this.msg(player, "Pot: " + Currency.format(pot), NamedTextColor.YELLOW);
            this.msg(player, "Total tickets: " + total, NamedTextColor.GRAY);
            this.msg(player, "Your tickets: " + mine, NamedTextColor.GRAY);
            this.msg(player, "Ticket price: " + Currency.format(price), NamedTextColor.GRAY);
            this.msg(player, "Buy: /ce lottery buy", NamedTextColor.AQUA);
        }
    }

    private void cmdMod(Player player, String[] args) {
        if (!this.plugin.getModerationManager().isEnabled()) {
            this.msg(player, "Moderation tools are disabled.", NamedTextColor.RED);
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("staff")) {
            if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.STAFFMODE)) {
                this.msg(player, "No permission.", NamedTextColor.RED);
                return;
            }
            this.plugin.getModerationManager().openStaffHub(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "vanish" -> this.cmdVanish(player, args);
            case "history" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.INSPECT)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod history <player>");
                if (target == null || target.getUniqueId() == null) {
                    return;
                }
                player.sendMessage(Component.text("=== Moderation History: " + this.safeName(target) + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                for (var action : this.plugin.getModerationManager().getHistory(target.getUniqueId(), 10)) {
                    player.sendMessage(Component.text(action.actionType() + " - " + (action.reason() == null ? "No reason" : action.reason()), NamedTextColor.GRAY));
                }
            }
            case "note" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.NOTE)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod note <player> <note>");
                if (target == null || args.length < 4) {
                    return;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if (this.plugin.getModerationManager().note(player, target, reason, "command")) {
                    this.msg(player, "Note added for " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "warn" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.WARN)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod warn <player> <reason>");
                if (target == null || args.length < 4) {
                    return;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if (this.plugin.getModerationManager().warn(player, target, reason, "command")) {
                    this.msg(player, "Warned " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "kick" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.KICK)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                if (args.length < 4) {
                    this.msg(player, "Usage: /ce mod kick <player> <reason>", NamedTextColor.RED);
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    this.msg(player, "Target must be online.", NamedTextColor.RED);
                    return;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                this.plugin.getModerationManager().kick(player, target, reason, "command");
                this.msg(player, "Kicked " + target.getName() + ".", NamedTextColor.YELLOW);
            }
            case "mute" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.MUTE)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                if (args.length < 5) {
                    this.msg(player, "Usage: /ce mod mute <player> <duration> <reason>", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod mute <player> <duration> <reason>");
                if (target == null) {
                    return;
                }
                long duration = this.plugin.getModerationManager().parseDuration(args[3]);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                if (this.plugin.getModerationManager().mute(player, target, duration, reason, "command")) {
                    this.msg(player, "Muted " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "unmute" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.MUTE)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod unmute <player>");
                if (target != null && this.plugin.getModerationManager().unmute(player, target, "command")) {
                    this.msg(player, "Unmuted " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "ban" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.BAN)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                if (args.length < 5) {
                    this.msg(player, "Usage: /ce mod ban <player> <duration|permanent> <reason>", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod ban <player> <duration|permanent> <reason>");
                if (target == null) {
                    return;
                }
                long duration = this.plugin.getModerationManager().parseDuration(args[3]);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                if (this.plugin.getModerationManager().ban(player, target, duration, reason, "command")) {
                    this.msg(player, "Banned " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "unban" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.BAN)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                if (args.length < 3) {
                    this.msg(player, "Usage: /ce mod unban <player>", NamedTextColor.RED);
                    return;
                }
                if (this.plugin.getModerationManager().unban(player, args[2], "command")) {
                    this.msg(player, "Unbanned " + args[2] + ".", NamedTextColor.GREEN);
                }
            }
            case "freeze" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.FREEZE)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod freeze <player>");
                if (target != null && this.plugin.getModerationManager().freeze(player, target, "Frozen by staff", "command")) {
                    this.msg(player, "Frozen " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "unfreeze" -> {
                if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.FREEZE)) {
                    this.msg(player, "No permission.", NamedTextColor.RED);
                    return;
                }
                OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod unfreeze <player>");
                if (target != null && this.plugin.getModerationManager().unfreeze(player, target, "command")) {
                    this.msg(player, "Unfrozen " + this.safeName(target) + ".", NamedTextColor.GREEN);
                }
            }
            case "role" -> this.cmdModRole(player, args);
            case "puppet" -> this.cmdPuppet(player, args);
            case "inventory" -> this.cmdInventoryRollback(player, args);
            default -> this.msg(player, "Usage: /ce mod <vanish|history|note|warn|kick|mute|unmute|ban|unban|freeze|unfreeze|staff|role|puppet|inventory>", NamedTextColor.GRAY);
        }
    }

    private void cmdVanish(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.VANISH)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        boolean vanished;
        if (args.length < 3) {
            vanished = this.plugin.getModerationManager().toggleVanish(player);
        } else if (args[2].equalsIgnoreCase("on")) {
            vanished = this.plugin.getModerationManager().setVanished(player, true, "command");
        } else if (args[2].equalsIgnoreCase("off")) {
            vanished = this.plugin.getModerationManager().setVanished(player, false, "command");
        } else {
            this.msg(player, "Usage: /ce mod vanish [on|off]", NamedTextColor.RED);
            return;
        }
        this.msg(player, vanished ? "True vanish enabled." : "True vanish disabled.", vanished ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
    }

    private void cmdReport(Player player, String[] args) {
        if (args.length < 3) {
            this.msg(player, "Usage: /ce report <player> <reason>", NamedTextColor.RED);
            return;
        }
        OfflinePlayer target = this.resolveOffline(args, 1, player, "/ce report <player> <reason>");
        if (target == null) {
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (this.plugin.getModerationManager().createReport(player, target, reason)) {
            this.msg(player, "Report submitted for " + this.safeName(target) + ".", NamedTextColor.GREEN);
        } else {
            this.msg(player, "Could not submit that report.", NamedTextColor.RED);
        }
    }

    private void cmdAnalytics(Player player, String[] args) {
        if (!player.hasPermission("crowns.analytics")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length == 1) {
            this.plugin.getMenuManager().openAnalyticsMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("economy")) {
            if (!player.hasPermission("crowns.analytics.economy")) {
                this.msg(player, "No permission.", NamedTextColor.RED);
                return;
            }
            PlaytimePeriod period = args.length >= 3 ? PlaytimePeriod.fromKey(args[2]) : PlaytimePeriod.ALL;
            this.sendEconomySummary(player, period);
            return;
        }
        if (args[1].equalsIgnoreCase("top")) {
            PlaytimePeriod period = args.length >= 3 ? PlaytimePeriod.fromKey(args[2]) : PlaytimePeriod.ALL;
            List<PlaytimeEntry> entries = this.plugin.getPlaytimeManager().getTopEntries(period, 10);
            player.sendMessage(Component.text("=== Playtime Leaderboard: " + period.label() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            int rank = 1;
            for (PlaytimeEntry entry : entries) {
                player.sendMessage(Component.text(rank + ". " + entry.name() + " - " + this.plugin.getMenuManager().formatDuration(entry.seconds()), NamedTextColor.GRAY));
                rank++;
            }
            if (entries.isEmpty()) {
                this.msg(player, "No playtime recorded yet.", NamedTextColor.GRAY);
            }
            return;
        }
        if (args[1].equalsIgnoreCase("player")) {
            if (!player.hasPermission("crowns.analytics.others")) {
                this.msg(player, "No permission.", NamedTextColor.RED);
                return;
            }
            if (args.length < 3) {
                this.msg(player, "Usage: /ce analytics player <name>", NamedTextColor.RED);
                return;
            }
            this.plugin.getPlaytimeManager().getSnapshotByName(args[2]).ifPresentOrElse(
                    snapshot -> this.sendSnapshot(player, snapshot),
                    () -> this.msg(player, "Player not found in analytics data.", NamedTextColor.RED)
            );
            return;
        }
        this.msg(player, "Usage: /ce analytics | /ce analytics top <today|7d|30d|all> | /ce analytics player <name> | /ce analytics economy <today|7d|30d|all>", NamedTextColor.GRAY);
    }

    private void cmdAfk(Player player) {
        if (!player.hasPermission("crowns.afk")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        boolean afk = this.plugin.getAfkManager().toggle(player.getUniqueId());
        PlayerListener.refreshTag(this.plugin, player);
        this.msg(player, afk ? "You are now marked AFK." : "You are no longer marked AFK.", afk ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
    }

    private void cmdInbox(Player player) {
        if (!player.hasPermission("crowns.inbox")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        this.plugin.getMenuManager().openInboxMenu(player);
    }

    private void cmdEvent(Player player, String[] args) {
        if (!player.hasPermission("crowns.event")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (!this.plugin.getEventManager().isEnabled()) {
            this.msg(player, this.plugin.getEventManager().getTitle() + " is disabled.", NamedTextColor.RED);
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("board")) {
            this.plugin.getMenuManager().openEventBoard(player);
            return;
        }
        if (args[1].equalsIgnoreCase("status")) {
            this.sendEventStatus(player);
            return;
        }
        if (args[1].equalsIgnoreCase("rewards")) {
            this.plugin.getMenuManager().openEventRewardsMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("guide")) {
            this.plugin.getMenuManager().openEventGuideMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("archive")) {
            this.plugin.getMenuManager().openEventArchiveMenu(player);
            return;
        }
        if (args[1].equalsIgnoreCase("turnin")) {
            if (args.length < 3 || !(args[2].equalsIgnoreCase("hand") || args[2].equalsIgnoreCase("all"))) {
                this.msg(player, "Usage: /ce event turnin <hand|all>", NamedTextColor.RED);
                return;
            }
            int points = args[2].equalsIgnoreCase("hand")
                    ? this.plugin.getEventManager().turnInHand(player)
                    : this.plugin.getEventManager().turnInInventory(player);
            if (points > 0) {
                this.msg(player, "Turned in relics worth " + points + " point(s).", NamedTextColor.GREEN);
            } else {
                this.msg(player, "You do not have any " + this.plugin.getEventManager().getMenuLabel() + " relics to turn in.", NamedTextColor.RED);
            }
            return;
        }
        if (args[1].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("crowns.event.admin")) {
                this.msg(player, "No permission.", NamedTextColor.RED);
                return;
            }
            if (args.length == 2) {
                this.plugin.getMenuManager().openEventAdminMenu(player);
                return;
            }
            if (args[2].equalsIgnoreCase("schedule")) {
                if (args.length < 4) {
                    this.msg(player, "Usage: /ce event admin schedule <10m|1h|yyyy-MM-ddTHH:mm>", NamedTextColor.RED);
                    return;
                }
                String timeInput = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if (this.plugin.getEventManager().scheduleFromInput(timeInput)) {
                    this.msg(player, this.plugin.getEventManager().getDimensionName() + " opening scheduled.", NamedTextColor.GREEN);
                } else {
                    this.msg(player, "Could not parse that time. Try 10m, 1h, or 2026-04-10T20:00.", NamedTextColor.RED);
                }
                return;
            }
            if (args[2].equalsIgnoreCase("start")) {
                this.plugin.getEventManager().forceStart();
                this.msg(player, this.plugin.getEventManager().getTitle() + " started.", NamedTextColor.GREEN);
                return;
            }
            if (args[2].equalsIgnoreCase("pause")) {
                if (this.plugin.getEventManager().togglePause()) {
                    this.msg(player, this.plugin.getEventManager().getMenuLabel() + " state updated.", NamedTextColor.YELLOW);
                } else {
                    this.msg(player, "That event cannot be paused or resumed right now.", NamedTextColor.RED);
                }
                return;
            }
            if (args[2].equalsIgnoreCase("end")) {
                if (this.plugin.getEventManager().endNow()) {
                    this.msg(player, this.plugin.getEventManager().getTitle() + " ended.", NamedTextColor.YELLOW);
                } else {
                    this.msg(player, "That event is already ended.", NamedTextColor.RED);
                }
                return;
            }
            this.msg(player, "Usage: /ce event admin | schedule <time> | start | pause | end", NamedTextColor.GRAY);
            return;
        }
        this.msg(player, "Usage: /ce event | status | board | guide | archive | rewards | turnin <hand|all> | admin", NamedTextColor.GRAY);
    }

    private void sendEventStatus(Player player) {
        player.sendMessage(Component.text("=== " + this.plugin.getEventManager().getTitle() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        this.msg(player, "State: " + this.plugin.getEventManager().getStatusLabel(), NamedTextColor.YELLOW);
        this.msg(player, this.plugin.getEventManager().getStatusDescription(), NamedTextColor.GRAY);
        this.msg(player, "Server relic points: " + this.plugin.getEventManager().getTotalRelics(), NamedTextColor.RED);
        this.msg(player, "Participants: " + this.plugin.getEventManager().getParticipantCount(), NamedTextColor.GRAY);
        List<com.xkstudios.crowns.event.EventManager.CollectorEntry> leaders = this.plugin.getEventManager().getTopCollectors(3);
        if (leaders.isEmpty()) {
            this.msg(player, "No relic collectors recorded yet.", NamedTextColor.GRAY);
            return;
        }
        this.msg(player, "Top collectors:", NamedTextColor.GOLD);
        int rank = 1;
        for (com.xkstudios.crowns.event.EventManager.CollectorEntry collector : leaders) {
            this.msg(player, rank + ". " + collector.name() + " - " + collector.relics() + " points", NamedTextColor.GRAY);
            rank++;
        }
    }

    private void cmdInvsee(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.INSPECT)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            this.msg(player, "Usage: /ce invsee <player>", NamedTextColor.RED);
            return;
        }
        OfflinePlayer target = this.resolveOffline(args, 1, player, "/ce invsee <player>");
        if (target == null) {
            return;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            this.msg(player, "That's your own inventory.", NamedTextColor.GRAY);
            return;
        }
        if (this.plugin.getModerationManager().openInventoryView(player, target)) {
            this.msg(player, "Viewing " + this.safeName(target) + "'s inventory.", NamedTextColor.YELLOW);
        } else {
            this.msg(player, "Could not open that inventory.", NamedTextColor.RED);
        }
    }

    private void cmdEnderchest(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.INSPECT)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            this.msg(player, "Usage: /ce echest <player>", NamedTextColor.RED);
            return;
        }
        OfflinePlayer target = this.resolveOffline(args, 1, player, "/ce echest <player>");
        if (target == null) {
            return;
        }
        if (this.plugin.getModerationManager().openEnderChestView(player, target)) {
            this.msg(player, "Viewing " + this.safeName(target) + "'s ender chest.", NamedTextColor.YELLOW);
        } else {
            this.msg(player, "Could not open that ender chest.", NamedTextColor.RED);
        }
    }

    private void cmdClearItems(Player player, String[] args) {
        if (!player.hasPermission("crowns.admin")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        int radius = args.length >= 2 ? Integer.parseInt(args[1]) : 0;
        int cleared = this.plugin.getEntityManager().clearItems(player.getWorld(), player.getLocation(), radius);
        this.msg(player, "Cleared " + cleared + " ground items" + (String)(radius > 0 ? " within " + radius + " blocks" : "") + ".", NamedTextColor.GREEN);
    }

    private void cmdClearMobs(Player player, String[] args) {
        if (!player.hasPermission("crowns.admin")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        int radius = args.length >= 2 ? Integer.parseInt(args[1]) : 0;
        int cleared = this.plugin.getEntityManager().clearMobs(player.getWorld(), player.getLocation(), radius);
        this.msg(player, "Cleared " + cleared + " hostile mobs" + (String)(radius > 0 ? " within " + radius + " blocks" : "") + ".", NamedTextColor.GREEN);
    }

    private void cmdEntities(Player player) {
        if (!player.hasPermission("crowns.admin")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        String breakdown = this.plugin.getEntityManager().getEntityBreakdown(player.getWorld());
        player.sendMessage((Component)Component.text((String)("=== Entity Count (" + player.getWorld().getName() + ") ==="), (TextColor)NamedTextColor.GOLD));
        for (String line : breakdown.split("\n")) {
            player.sendMessage((Component)Component.text((String)line, (TextColor)NamedTextColor.GRAY));
        }
    }

    private void cmdAdmin(Player player, String[] args) {
        if (!player.hasPermission("crowns.admin")) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 4) {
            this.msg(player, "Usage: /ce admin <give|take|set> <player> <amount>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayer((String)args[2]);
        if (target == null) {
            this.msg(player, "Player not found.", NamedTextColor.RED);
            return;
        }
        try {
            long amount = Currency.parse(args[3]);
            PlayerData pd = this.plugin.getDataManager().getOrCreate(target.getUniqueId(), target.getName());
            switch (args[1].toLowerCase()) {
                case "give": {
                    this.plugin.getEconomy().adminGive(player, target, amount);
                    this.msg(player, "Gave " + Currency.format(amount) + " to " + target.getName(), NamedTextColor.GREEN);
                    break;
                }
                case "take": {
                    if (this.plugin.getEconomy().adminTake(player, target, amount)) {
                        this.msg(player, "Took " + Currency.format(amount) + " from " + target.getName(), NamedTextColor.YELLOW);
                    } else {
                        this.msg(player, "Target does not have enough funds.", NamedTextColor.RED);
                    }
                    break;
                }
                case "set": {
                    this.plugin.getEconomy().adminSet(player, target, amount);
                    this.msg(player, "Set " + target.getName() + " to " + Currency.format(amount), NamedTextColor.GREEN);
                    break;
                }
                default: {
                    this.msg(player, "Usage: give, take, or set", NamedTextColor.RED);
                    break;
                }
            }
        }
        catch (NumberFormatException e) {
            this.msg(player, "Invalid amount.", NamedTextColor.RED);
        }
    }

    private void cmdModRole(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.ROLES)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 4) {
            this.msg(player, "Usage: /ce mod role <player> <role> | /ce mod role clear <player>", NamedTextColor.RED);
            return;
        }
        if (args[2].equalsIgnoreCase("clear")) {
            OfflinePlayer target = this.resolveOffline(args, 3, player, "/ce mod role clear <player>");
            if (target != null && this.plugin.getModerationManager().clearRole(player, target)) {
                this.msg(player, "Role cleared for " + this.safeName(target) + ".", NamedTextColor.YELLOW);
            }
            return;
        }
        OfflinePlayer target = this.resolveOffline(args, 2, player, "/ce mod role <player> <role>");
        if (target != null && this.plugin.getModerationManager().assignRole(player, target, args[3])) {
            this.msg(player, "Role set for " + this.safeName(target) + ".", NamedTextColor.GREEN);
        } else if (target != null) {
            this.msg(player, "Could not assign that role.", NamedTextColor.RED);
        }
    }

    private void cmdPuppet(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.PUPPET)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 3) {
            this.msg(player, "Usage: /ce mod puppet <player|stop>", NamedTextColor.RED);
            return;
        }
        if (args[2].equalsIgnoreCase("stop")) {
            if (this.plugin.getModerationManager().stopPuppeteer(player.getUniqueId(), "stopped by staff")) {
                this.msg(player, "Puppeteering ended.", NamedTextColor.YELLOW);
            } else {
                this.msg(player, "You are not puppeteering anyone.", NamedTextColor.RED);
            }
            return;
        }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            this.msg(player, "Target must be online.", NamedTextColor.RED);
            return;
        }
        if (this.plugin.getModerationManager().startPuppeteer(player, target)) {
            this.msg(player, "Now puppeteering " + target.getName() + ".", NamedTextColor.LIGHT_PURPLE);
        } else {
            this.msg(player, "Could not start puppeteering.", NamedTextColor.RED);
        }
    }

    private void cmdInventoryRollback(Player player, String[] args) {
        if (!this.plugin.getModerationManager().hasCapability(player, com.xkstudios.crowns.moderation.StaffCapability.ROLLBACK)) {
            this.msg(player, "No permission.", NamedTextColor.RED);
            return;
        }
        if (args.length < 5 || !args[2].equalsIgnoreCase("rollback")) {
            this.msg(player, "Usage: /ce mod inventory rollback <player> <snapshot>", NamedTextColor.RED);
            return;
        }
        OfflinePlayer target = this.resolveOffline(args, 3, player, "/ce mod inventory rollback <player> <snapshot>");
        if (target == null) {
            return;
        }
        try {
            long snapshotId = Long.parseLong(args[4]);
            if (this.plugin.getModerationManager().rollbackSnapshot(player, target, snapshotId)) {
                this.msg(player, "Rolled back snapshot #" + snapshotId + " for " + this.safeName(target) + ".", NamedTextColor.GREEN);
            } else {
                this.msg(player, "Could not roll back that snapshot.", NamedTextColor.RED);
            }
        } catch (NumberFormatException e) {
            this.msg(player, "Snapshot ID must be a number.", NamedTextColor.RED);
        }
    }

    private OfflinePlayer resolveOffline(String[] args, int index, Player actor, String usage) {
        if (args.length <= index) {
            this.msg(actor, "Usage: " + usage, NamedTextColor.RED);
            return null;
        }
        Player online = Bukkit.getPlayer(args[index]);
        if (online != null) {
            return online;
        }
        UUID knownUuid = this.plugin.getDataManager().findUuidByName(args[index]);
        if (knownUuid != null) {
            return Bukkit.getOfflinePlayer(knownUuid);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[index]);
        if (offline == null || (offline.getName() == null && offline.getUniqueId() == null)) {
            this.msg(actor, "Player not found.", NamedTextColor.RED);
            return null;
        }
        return offline;
    }

    private String safeName(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString().substring(0, 8) : target.getName();
    }

    private void sendSnapshot(Player player, PlaytimeSnapshot snapshot) {
        player.sendMessage(Component.text("=== Playtime: " + snapshot.name() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        this.msg(player, "Lifetime: " + this.plugin.getMenuManager().formatDuration(snapshot.lifetimeSeconds()), NamedTextColor.YELLOW);
        this.msg(player, "Current session: " + this.plugin.getMenuManager().formatDuration(snapshot.currentSessionSeconds()), NamedTextColor.GRAY);
        this.msg(player, "Today: " + this.plugin.getMenuManager().formatDuration(snapshot.todaySeconds()), NamedTextColor.GRAY);
        this.msg(player, "Last 7d: " + this.plugin.getMenuManager().formatDuration(snapshot.last7DaysSeconds()), NamedTextColor.GRAY);
        this.msg(player, "Last 30d: " + this.plugin.getMenuManager().formatDuration(snapshot.last30DaysSeconds()), NamedTextColor.GRAY);
        this.msg(player, "First join: " + this.formatTimestamp(snapshot.firstJoinAt()), NamedTextColor.GRAY);
        this.msg(player, "Last seen: " + (snapshot.online() ? "Online now" : this.formatTimestamp(snapshot.lastQuitAt())), NamedTextColor.GRAY);
    }

    private void sendEconomySummary(Player player, PlaytimePeriod period) {
        EconomyLedgerSummary summary = this.plugin.getEconomyLedgerManager().getSummary(period);
        player.sendMessage(Component.text("=== Economy Insights: " + period.label() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        this.msg(player, "Total sources: " + Currency.format(summary.totalSources()), NamedTextColor.GREEN);
        this.msg(player, "Total sinks: " + Currency.format(summary.totalSinks()), NamedTextColor.RED);
        this.msg(player, "Net created: " + Currency.format(summary.netCreated()), summary.netCreated() >= 0L ? NamedTextColor.YELLOW : NamedTextColor.RED);
        this.msg(player, "Top sources:", NamedTextColor.GREEN);
        summary.sources().entrySet().stream().limit(5).forEach(entry ->
                this.msg(player, "  " + entry.getKey() + ": " + Currency.format(entry.getValue()), NamedTextColor.GRAY));
        this.msg(player, "Top sinks:", NamedTextColor.RED);
        summary.sinks().entrySet().stream().limit(5).forEach(entry ->
                this.msg(player, "  " + entry.getKey() + ": " + Currency.format(entry.getValue()), NamedTextColor.GRAY));
    }

    private String formatTimestamp(long millis) {
        if (millis <= 0L) {
            return "Never";
        }
        return DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private void sendHelp(Player player) {
        String[] lines;
        player.sendMessage(Component.text("=== Crowns Economy ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        String eventLabel = this.plugin.getEventManager().getMenuLabel();
        String guideLabel = this.plugin.getEventManager().getGuideTitle();
        for (String l : lines = new String[]{
                "/ce - Open the main Crowns GUI",
                "/ce bal [player] - Check balance",
                "/ce pay <player> <amount> - Send money",
                "/ce top - Economy leaderboard",
                "/ce afk - Toggle your AFK tag",
                "/ce inbox - Read your stored notifications",
                "/ce event - Open " + eventLabel + " board",
                "/ce event guide - Open the " + guideLabel,
                "/ce event status - See countdown or live event status",
                "/ce event archive - Browse past or inactive event pages",
                "/ce event rewards - Claim " + eventLabel + " rewards",
                "/ce event turnin <hand|all> - Turn relic items into score",
                "/ce event admin ... - Staff event controls",
                "/ce relics - Open your relic progress board",
                "/ce auction - Open auction house",
                "/ce auction sell <price> [hours] - List held item",
                "/ce stalls - Open rentable market stalls",
                "/ce stalls rent | my | browse | reclaim",
                "/ce report <player> <reason> - Report a player",
                "/ce mod - Open the moderation hub",
                "/ce mod vanish [on|off] - Toggle true vanish",
                "/ce mod warn|note|mute|ban|freeze <player> ...",
                "/ce mod role <player> <role> - Assign staff roles",
                "/ce mod puppet <player> - Take control of a player",
                "/ce analytics - Open analytics GUI",
                "/ce analytics top <today|7d|30d|all> - Playtime leaderboard",
                "/ce analytics player <name> - Player playtime lookup",
                "/ce analytics economy <today|7d|30d|all> - Economy sources and sinks",
                "/ce shop create <price> - Create shop (look at chest)",
                "/ce jobs - View jobs",
                "/ce lottery - View current lottery",
                "/ce invsee <player> - Inspect inventory (admin)",
                "/ce echest <player> - Inspect ender chest (admin)"
        }) {
            player.sendMessage(Component.text("  " + l, NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text("  Amounts: 500 = pennies, 5s = shillings, 1c = crown, 2c50s10p = mixed", NamedTextColor.DARK_GRAY));
    }

    private void msg(Player p, String m, NamedTextColor c) {
        p.sendMessage(Component.text(m, c));
    }

    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            return List.of("menu", "bal", "pay", "top", "afk", "inbox", "event", "relics", "shop", "auction", "stalls", "report", "mod", "analytics", "bounty", "jobs", "coinflip", "lottery", "invsee", "echest", "clearitems", "clearmobs", "entities", "admin", "help")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("shop")) {
                return List.of("create", "remove", "list").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("auction")) {
                return List.of("sell").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("stalls") || args[0].equalsIgnoreCase("stall")) {
                List<String> base = new ArrayList<>(List.of("rent", "my", "browse", "reclaim"));
                if (sender instanceof Player player && player.hasPermission("crowns.stalls.admin")) {
                    base.add("admin");
                }
                return base.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("analytics")) {
                return List.of("top", "player", "economy").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("event")) {
                List<String> base = new ArrayList<>(List.of("status", "board", "rewards", "turnin"));
                base.add("guide");
                if (sender instanceof Player player && player.hasPermission("crowns.event.admin")) {
                    base.add("admin");
                }
                return base.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("mod")) {
                return List.of("vanish", "history", "note", "warn", "kick", "mute", "unmute", "ban", "unban", "freeze", "unfreeze", "staff", "role", "puppet", "inventory")
                        .stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("bounty")) {
                ArrayList<String> opts = new ArrayList<String>();
                opts.add("list");
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
                return opts.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("jobs")) {
                return List.of("accept").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("coinflip")) {
                ArrayList<String> opts = new ArrayList<String>();
                opts.add("accept");
                opts.add("deny");
                Bukkit.getOnlinePlayers().forEach(p -> {
                    Player sp;
                    if (sender instanceof Player && !p.equals((Object)(sp = (Player)sender))) {
                        opts.add(p.getName());
                    }
                });
                return opts.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("lottery")) {
                return List.of("buy").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("admin")) {
                return List.of("give", "take", "set").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("pay") || args[0].equalsIgnoreCase("bal") || args[0].equalsIgnoreCase("balance") || args[0].equalsIgnoreCase("invsee") || args[0].equalsIgnoreCase("echest") || args[0].equalsIgnoreCase("ec") || args[0].equalsIgnoreCase("report")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("vanish")) {
                return List.of("on", "off").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("turnin")) {
                return List.of("hand", "all").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("admin")) {
                return List.of("schedule", "start", "pause", "end").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("mod") && List.of("history", "note", "warn", "kick", "mute", "unmute", "ban", "freeze", "unfreeze", "role", "puppet").contains(args[1].toLowerCase())) {
                if (args[1].equalsIgnoreCase("puppet")) {
                    ArrayList<String> names = new ArrayList<>();
                    names.add("stop");
                    Bukkit.getOnlinePlayers().forEach(p -> {
                        if (!p.equals(playerOrNull(sender))) {
                            names.add(p.getName());
                        }
                    });
                    return names.stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
                }
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("inventory")) {
                return List.of("rollback").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("unban")) {
                return List.of("<player>").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if ((args[0].equalsIgnoreCase("stalls") || args[0].equalsIgnoreCase("stall")) && args[1].equalsIgnoreCase("browse")) {
                ArrayList<String> names = new ArrayList<String>();
                names.add("*");
                Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
                return names.stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("pay") || args[0].equalsIgnoreCase("coinflip") || args[0].equalsIgnoreCase("bounty")) {
                return List.of("100", "1s", "5s", "1c").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("analytics") && args[1].equalsIgnoreCase("top")) {
                return List.of("today", "7d", "30d", "all").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("analytics") && args[1].equalsIgnoreCase("economy")) {
                return List.of("today", "7d", "30d", "all").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("admin")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("admin") && args[2].equalsIgnoreCase("schedule")) {
            return List.of("10m", "1h", "2026-04-10T20:00").stream().filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("role")) {
            return this.plugin.getModerationManager().getRoleKeys().stream().filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("mod") && List.of("mute", "ban").contains(args[1].toLowerCase())) {
            List<String> defaults = args[1].equalsIgnoreCase("mute") ? this.plugin.getModerationManager().getDefaultMuteDurations() : this.plugin.getModerationManager().getDefaultBanDurations();
            return defaults.stream().filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("inventory") && args[2].equalsIgnoreCase("rollback")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("mod") && args[1].equalsIgnoreCase("inventory") && args[2].equalsIgnoreCase("rollback")) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
            return this.plugin.getModerationManager().getSnapshotIds(target.getUniqueId()).stream().map(String::valueOf).filter(s -> s.startsWith(args[4].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("auction") && args[1].equalsIgnoreCase("sell")) {
            return List.of("6", "12", "24", "48", "72").stream().filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        return null;
    }

    private Player playerOrNull(CommandSender sender) {
        return sender instanceof Player p ? p : null;
    }
}
