package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.analytics.EconomyLedgerSummary;
import com.xkstudios.crowns.analytics.PlaytimeEntry;
import com.xkstudios.crowns.analytics.PlaytimePeriod;
import com.xkstudios.crowns.analytics.PlaytimeSnapshot;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.moderation.ModerationReport;
import com.xkstudios.crowns.moderation.StaffCapability;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final CrownsPlugin plugin;

    public AdminCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            this.plugin.getModerationManager().openStaffHub(player);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "staffmode" -> this.toggleStaffMode(player);
            case "vanish" -> this.toggleVanish(player, args);
            case "freeze" -> this.freeze(player, args);
            case "unfreeze" -> this.unfreeze(player, args);
            case "mute" -> this.mute(player, args);
            case "unmute" -> this.unmute(player, args);
            case "warn" -> this.warn(player, args);
            case "kick" -> this.kick(player, args);
            case "ban" -> this.ban(player, args);
            case "unban" -> this.unban(player, args);
            case "note" -> this.note(player, args);
            case "reports" -> this.reports(player);
            case "invsee" -> this.invsee(player, args);
            case "echest" -> this.echest(player, args);
            case "analytics" -> this.analytics(player, args);
            case "playtime" -> this.playtime(player, args);
            case "clearitems" -> this.clearItems(player, args);
            case "clearmobs" -> this.clearMobs(player, args);
            case "entities" -> this.entities(player);
            default -> {
                this.sendHelp(player);
                yield true;
            }
        };
    }

    private boolean toggleStaffMode(Player player) {
        if (!this.require(player, StaffCapability.STAFFMODE)) {
            return true;
        }
        boolean enabled = this.plugin.getModerationManager().toggleStaffMode(player);
        this.msg(player, enabled ? "Staff mode enabled." : "Staff mode disabled.", enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        return true;
    }

    private boolean toggleVanish(Player player, String[] args) {
        if (!this.require(player, StaffCapability.VANISH)) {
            return true;
        }
        boolean vanished;
        if (args.length >= 2) {
            vanished = args[1].equalsIgnoreCase("on")
                    || (!args[1].equalsIgnoreCase("off") && !this.plugin.getModerationManager().isVanished(player.getUniqueId()));
            this.plugin.getModerationManager().setVanished(player, vanished, "command");
        } else {
            vanished = this.plugin.getModerationManager().toggleVanish(player);
        }
        this.msg(player, vanished ? "Vanish enabled." : "Vanish disabled.", vanished ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        return true;
    }

    private boolean freeze(Player player, String[] args) {
        if (!this.require(player, StaffCapability.FREEZE) || args.length < 2) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Frozen by staff";
        boolean ok = this.plugin.getModerationManager().freeze(player, target, reason, "command");
        this.msg(player, ok ? "Player frozen." : "Could not freeze that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean unfreeze(Player player, String[] args) {
        if (!this.require(player, StaffCapability.FREEZE) || args.length < 2) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean ok = this.plugin.getModerationManager().unfreeze(player, target, "command");
        this.msg(player, ok ? "Player unfrozen." : "Could not unfreeze that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean mute(Player player, String[] args) {
        if (!this.require(player, StaffCapability.MUTE) || args.length < 4) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        long duration = this.plugin.getModerationManager().parseDuration(args[2]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        boolean ok = this.plugin.getModerationManager().mute(player, target, duration, reason, "command");
        this.msg(player, ok ? "Player muted." : "Could not mute that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean unmute(Player player, String[] args) {
        if (!this.require(player, StaffCapability.MUTE) || args.length < 2) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean ok = this.plugin.getModerationManager().unmute(player, target, "command");
        this.msg(player, ok ? "Player unmuted." : "Could not unmute that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean warn(Player player, String[] args) {
        if (!this.require(player, StaffCapability.WARN) || args.length < 3) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = this.plugin.getModerationManager().warn(player, target, reason, "command");
        this.msg(player, ok ? "Warning issued." : "Could not warn that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean kick(Player player, String[] args) {
        if (!this.require(player, StaffCapability.KICK) || args.length < 3) {
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            this.msg(player, "That player must be online to kick them.", NamedTextColor.RED);
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = this.plugin.getModerationManager().kick(player, target, reason, "command");
        this.msg(player, ok ? "Player kicked." : "Could not kick that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean ban(Player player, String[] args) {
        if (!this.require(player, StaffCapability.BAN) || args.length < 4) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        long duration = args[2].equalsIgnoreCase("permanent") ? -1L : this.plugin.getModerationManager().parseDuration(args[2]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        boolean ok = this.plugin.getModerationManager().ban(player, target, duration, reason, "command");
        this.msg(player, ok ? "Player banned." : "Could not ban that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean unban(Player player, String[] args) {
        if (!this.require(player, StaffCapability.BAN) || args.length < 2) {
            return true;
        }
        boolean ok = this.plugin.getModerationManager().unban(player, args[1], "command");
        this.msg(player, ok ? "Player unbanned." : "Could not unban that player.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean note(Player player, String[] args) {
        if (!this.require(player, StaffCapability.NOTE) || args.length < 3) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = this.plugin.getModerationManager().note(player, target, note, "command");
        this.msg(player, ok ? "Note saved." : "Could not save that note.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean reports(Player player) {
        if (!this.require(player, StaffCapability.REPORTS)) {
            return true;
        }
        List<ModerationReport> reports = this.plugin.getModerationManager().getOpenReports();
        if (reports.isEmpty()) {
            this.msg(player, "No open reports.", NamedTextColor.GRAY);
            return true;
        }
        player.sendMessage(Component.text("=== Open Reports ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (ModerationReport report : reports) {
            player.sendMessage(Component.text("#" + report.id() + " " + report.reporterName() + " -> " + report.targetName() + ": " + report.reason(),
                    NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean invsee(Player player, String[] args) {
        if (!this.require(player, StaffCapability.INSPECT) || args.length < 2) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean ok = this.plugin.getModerationManager().openInventoryView(player, target);
        this.msg(player, ok ? "Opening inventory editor." : "Could not open that inventory.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean echest(Player player, String[] args) {
        if (!this.require(player, StaffCapability.INSPECT) || args.length < 2) {
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean ok = this.plugin.getModerationManager().openEnderChestView(player, target);
        this.msg(player, ok ? "Opening ender chest editor." : "Could not open that ender chest.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean analytics(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("economy")) {
            PlaytimePeriod period = args.length >= 3 ? this.parsePeriod(args[2]) : PlaytimePeriod.TODAY;
            EconomyLedgerSummary summary = this.plugin.getEconomyLedgerManager().getSummary(period);
            player.sendMessage(Component.text("=== Economy Analytics (" + period.name() + ") ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("Sources: " + Currency.format(summary.totalSources()), NamedTextColor.GREEN));
            player.sendMessage(Component.text("Sinks: " + Currency.format(summary.totalSinks()), NamedTextColor.RED));
            player.sendMessage(Component.text("Net: " + Currency.format(summary.totalSources() - summary.totalSinks()), NamedTextColor.YELLOW));
            return true;
        }
        this.msg(player, "Usage: /ca analytics economy [today|7d|30d|all]", NamedTextColor.RED);
        return true;
    }

    private boolean playtime(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("top")) {
            PlaytimePeriod period = args.length >= 3 ? this.parsePeriod(args[2]) : PlaytimePeriod.TODAY;
            List<PlaytimeEntry> leaders = this.plugin.getPlaytimeManager().getTopEntries(period, 10);
            player.sendMessage(Component.text("=== Playtime Leaders (" + period.name() + ") ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            for (int i = 0; i < leaders.size(); i++) {
                PlaytimeEntry entry = leaders.get(i);
                player.sendMessage(Component.text((i + 1) + ". " + entry.name() + " - " + this.formatSeconds(entry.seconds()), NamedTextColor.GRAY));
            }
            return true;
        }
        if (args.length >= 2) {
            Optional<PlaytimeSnapshot> snapshot = this.plugin.getPlaytimeManager().getSnapshotByName(args[1]);
            if (snapshot.isEmpty()) {
                this.msg(player, "Player not found.", NamedTextColor.RED);
                return true;
            }
            PlaytimeSnapshot value = snapshot.get();
            player.sendMessage(Component.text("=== Playtime: " + value.name() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("Lifetime: " + this.formatSeconds(value.lifetimeSeconds()), NamedTextColor.GREEN));
            player.sendMessage(Component.text("Session: " + this.formatSeconds(value.currentSessionSeconds()), NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Today: " + this.formatSeconds(value.todaySeconds()), NamedTextColor.GRAY));
            player.sendMessage(Component.text("7d: " + this.formatSeconds(value.last7DaysSeconds()), NamedTextColor.GRAY));
            player.sendMessage(Component.text("30d: " + this.formatSeconds(value.last30DaysSeconds()), NamedTextColor.GRAY));
            return true;
        }
        this.msg(player, "Usage: /ca playtime <player> or /ca playtime top [today|7d|30d|all]", NamedTextColor.RED);
        return true;
    }

    private boolean clearItems(Player player, String[] args) {
        int radius = args.length >= 2 ? this.parseInt(args[1], 64) : 64;
        int removed = this.plugin.getEntityManager().clearItems(player.getWorld(), player.getLocation(), radius);
        this.msg(player, "Cleared " + removed + " dropped items.", NamedTextColor.GREEN);
        return true;
    }

    private boolean clearMobs(Player player, String[] args) {
        int radius = args.length >= 2 ? this.parseInt(args[1], 64) : 64;
        int removed = this.plugin.getEntityManager().clearMobs(player.getWorld(), player.getLocation(), radius);
        this.msg(player, "Cleared " + removed + " hostile mobs.", NamedTextColor.GREEN);
        return true;
    }

    private boolean entities(Player player) {
        this.msg(player, this.plugin.getEntityManager().getEntityBreakdown(player.getWorld()), NamedTextColor.GRAY);
        return true;
    }

    private boolean require(Player player, StaffCapability capability) {
        if (this.plugin.getModerationManager().hasCapability(player, capability)) {
            return true;
        }
        this.msg(player, "You do not have permission to do that.", NamedTextColor.RED);
        return false;
    }

    private PlaytimePeriod parsePeriod(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "7d", "week", "days_7" -> PlaytimePeriod.DAYS_7;
            case "30d", "month", "days_30" -> PlaytimePeriod.DAYS_30;
            case "all" -> PlaytimePeriod.ALL;
            default -> PlaytimePeriod.TODAY;
        };
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String formatSeconds(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return hours + "h " + minutes + "m";
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("=== CrownsAdmin ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (String line : List.of(
                "/ca - Open staff hub",
                "/ca staffmode",
                "/ca vanish [on|off]",
                "/ca freeze <player>",
                "/ca unfreeze <player>",
                "/ca mute <player> <duration> <reason>",
                "/ca unmute <player>",
                "/ca warn <player> <reason>",
                "/ca kick <player> <reason>",
                "/ca ban <player> <duration|permanent> <reason>",
                "/ca unban <player>",
                "/ca note <player> <note>",
                "/ca reports",
                "/ca invsee <player>",
                "/ca echest <player>",
                "/ca analytics economy [today|7d|30d|all]",
                "/ca playtime <player>",
                "/ca playtime top [today|7d|30d|all]",
                "/ca clearitems [radius]",
                "/ca clearmobs [radius]",
                "/ca entities")) {
            player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private void msg(Player player, String message, NamedTextColor color) {
        player.sendMessage(Component.text(message, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return this.filter(List.of("staffmode", "vanish", "freeze", "unfreeze", "mute", "unmute", "warn", "kick", "ban", "unban", "note", "reports", "invsee", "echest", "clearitems", "clearmobs", "entities", "analytics", "playtime"), args[0]);
        }
        if (args.length == 2 && List.of("freeze", "unfreeze", "mute", "unmute", "warn", "kick", "ban", "note", "invsee", "echest", "playtime").contains(args[0].toLowerCase(Locale.ROOT))) {
            return this.onlineAndOfflineNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("vanish")) {
            return this.filter(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("analytics")) {
            return this.filter(List.of("economy"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("analytics") && args[1].equalsIgnoreCase("economy")) {
            return this.filter(List.of("today", "7d", "30d", "all"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("playtime")) {
            List<String> values = new ArrayList<>();
            values.add("top");
            values.addAll(this.onlineAndOfflineNames(args[1]));
            return this.filter(values, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("playtime") && args[1].equalsIgnoreCase("top")) {
            return this.filter(List.of("today", "7d", "30d", "all"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).distinct().toList();
    }

    private List<String> onlineAndOfflineNames(String prefix) {
        List<String> values = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            values.add(online.getName());
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null) {
                values.add(offline.getName());
            }
        }
        return this.filter(values, prefix);
    }
}
