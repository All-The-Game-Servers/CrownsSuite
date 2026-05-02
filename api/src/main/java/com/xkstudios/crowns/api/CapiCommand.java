package com.xkstudios.crowns.api;

import com.xkstudios.crowns.gui.SuiteGuiManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public class CapiCommand implements TabExecutor {
    private final CrownsAPIPlugin plugin;

    public CapiCommand(CrownsAPIPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ResourcePackService packs = CrownsAPI.getResourcePackService();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                SuiteGuiManager gui = CrownsAPI.getSuiteGui();
                if (gui != null) {
                    gui.openResourcePack(player);
                    return true;
                }
            }
            this.sendHelp(sender, label);
            return true;
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("pack")) {
            return this.handlePack(sender, label, args, packs);
        }
        if (root.equals("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the Crowns Suite GUI.");
                return true;
            }
            SuiteGuiManager gui = CrownsAPI.getSuiteGui();
            if (gui == null) {
                sender.sendMessage("The Crowns Suite GUI is not available right now.");
                return true;
            }
            gui.openHome(player);
            return true;
        }
        if (root.equals("status")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Crowns Suite Status");
                sender.sendMessage("Sections registered: " + CrownsAPI.getSections().size());
                sender.sendMessage("Economy: " + (CrownsAPI.getEconomy() == null ? "offline" : "online"));
                sender.sendMessage("Events: " + (CrownsAPI.getEvents() == null ? "offline" : "online"));
                sender.sendMessage("MMO: " + (CrownsAPI.getMmo() == null ? "offline" : "online"));
                sender.sendMessage("Terrain: " + (CrownsAPI.getTerrain() == null ? "offline" : "online"));
                return true;
            }
            SuiteGuiManager gui = CrownsAPI.getSuiteGui();
            if (gui == null) {
                sender.sendMessage("The Crowns Suite GUI is not available right now.");
                return true;
            }
            gui.openStatus(player);
            return true;
        }
        if (root.equals("help")) {
            this.sendHelp(sender, label);
            return true;
        }

        this.sendHelp(sender, label);
        return true;
    }

    private boolean handlePack(CommandSender sender, String label, String[] args, ResourcePackService packs) {
        if (packs == null) {
            sender.sendMessage("The resource-pack service is not available.");
            return true;
        }
        if (args.length == 1) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("Use /" + label + " pack prompt or /" + label + " pack link as a player.");
                return true;
            }
            packs.downloadPackToServer(sender);
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("info")) {
            sender.sendMessage("Crowns Suite Resource Pack");
            sender.sendMessage("Enabled: " + packs.isEnabled());
            sender.sendMessage("Version: " + packs.getVersion());
            sender.sendMessage("URL: " + (packs.getUrl().isBlank() ? "<not configured>" : packs.getUrl()));
            sender.sendMessage("Download URL: " + (packs.getDownloadUrl().isBlank() ? "<not configured>" : packs.getDownloadUrl()));
            sender.sendMessage("SHA1: " + (packs.getSha1().isBlank() ? "<not configured>" : packs.getSha1()));
            sender.sendMessage("GitHub: " + packs.getGitHubPageUrl());
            sender.sendMessage("Local Path: " + packs.getLocalPackPath());
            sender.sendMessage("ValhallaMMO Safe Mode: " + (!packs.allowClientPromptWithValhalla()));
            if (!packs.getPromptBlockReason().isBlank()) {
                sender.sendMessage("Prompt Status: " + packs.getPromptBlockReason());
            }
            return true;
        }
        if (action.equals("download")) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("You do not have permission to download the resource pack onto the server.");
                return true;
            }
            packs.downloadPackToServer(sender);
            return true;
        }
        if (action.equals("prompt")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can receive the direct pack prompt.");
                return true;
            }
            if (!packs.requestDownload(player)) {
                sender.sendMessage(packs.getPromptBlockReason().isBlank() ? "The resource pack is not ready to prompt." : packs.getPromptBlockReason());
            }
            return true;
        }
        if (action.equals("link")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can receive the manual install link.");
                return true;
            }
            packs.sendManualInstall(player);
            return true;
        }
        if (action.equals("broadcast")) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("You do not have permission to broadcast the resource pack.");
                return true;
            }
            if (!packs.canPromptPlayers()) {
                sender.sendMessage(packs.getPromptBlockReason().isBlank() ? "The resource pack is not ready to broadcast yet." : packs.getPromptBlockReason());
                return true;
            }
            packs.broadcastDownloadRequest();
            sender.sendMessage("Broadcast the resource-pack prompt to all online players.");
            return true;
        }
        if (action.equals("share")) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("You do not have permission to share the resource pack with other players.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("Usage: /" + label + " pack share <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("That player is not online.");
                return true;
            }
            if (!packs.canPromptPlayers()) {
                sender.sendMessage(packs.getPromptBlockReason().isBlank() ? "The resource pack is not ready to share yet." : packs.getPromptBlockReason());
                return true;
            }
            packs.shareDownload(target, sender instanceof Player player ? player : null);
            return true;
        }
        if (args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target != null) {
                if (!sender.hasPermission("crowns.api.admin")) {
                    sender.sendMessage("You do not have permission to send the pack to other players.");
                    return true;
                }
                if (!packs.canPromptPlayers()) {
                    sender.sendMessage(packs.getPromptBlockReason().isBlank() ? "The resource pack is not ready to share yet." : packs.getPromptBlockReason());
                    return true;
                }
                packs.shareDownload(target, sender instanceof Player player ? player : null);
                return true;
            }
        }

        sender.sendMessage("Usage: /" + label + " pack [download|prompt|info|link|broadcast|share <player>]");
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("CrownsAPI");
        sender.sendMessage("/" + label + " pack - download the current Crowns Suite resource pack onto the server");
        sender.sendMessage("/" + label + " pack download - same as /" + label + " pack");
        sender.sendMessage("/" + label + " pack prompt - send yourself the direct pack prompt");
        sender.sendMessage("/" + label + " pack link - show the manual install link");
        sender.sendMessage("/" + label + " pack info - show configured pack details");
        sender.sendMessage("/" + label + " gui - open the Crowns Suite home");
        sender.sendMessage("/" + label + " status - open the suite module status page");
        if (sender.hasPermission("crowns.api.admin")) {
            sender.sendMessage("/" + label + " pack share <player> - send the pack prompt to a player");
            sender.sendMessage("/" + label + " pack broadcast - send the pack prompt to everyone online");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("pack", "gui", "status", "help"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pack")) {
            List<String> base = new ArrayList<>(List.of("download", "prompt", "info", "link"));
            if (sender.hasPermission("crowns.api.admin")) {
                base.add("broadcast");
                base.add("share");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    base.add(player.getName());
                }
            }
            return StringUtil.copyPartialMatches(args[1], base, suggestions);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pack") && args[1].equalsIgnoreCase("share")) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return StringUtil.copyPartialMatches(args[2], names, suggestions);
        }
        return suggestions;
    }
}
