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
                this.sendModuleStatus(sender);
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
        if (root.equals("modules")) {
            if (sender instanceof Player player && CrownsAPI.getSuiteGui() != null) {
                CrownsAPI.getSuiteGui().openStatus(player);
                return true;
            }
            this.sendModuleStatus(sender);
            return true;
        }
        if (root.equals("downloads")) {
            this.sendDownloads(sender);
            return true;
        }
        if (root.equals("help")) {
            this.sendHelp(sender, label);
            return true;
        }

        this.sendHelp(sender, label);
        return true;
    }

    private void sendModuleStatus(CommandSender sender) {
        sender.sendMessage("Crowns Suite Module Status");
        sender.sendMessage("Sections registered: " + CrownsAPI.getSections().size());
        for (ModuleHealth health : CrownsAPI.getModuleHealth()) {
            ModuleDescriptor descriptor = health.descriptor();
            sender.sendMessage("- " + descriptor.displayName() + " " + descriptor.version() + " [" + health.state() + "] " + health.summary());
            if (!descriptor.providedServices().isEmpty()) {
                sender.sendMessage("  services: " + String.join(", ", descriptor.providedServices()));
            }
            for (String warning : health.warnings()) {
                sender.sendMessage("  warning: " + warning);
            }
        }
    }

    private void sendDownloads(CommandSender sender) {
        ResourcePackService packs = CrownsAPI.getResourcePackService();
        sender.sendMessage("Crowns Suite Downloads");
        sender.sendMessage("GitHub downloads: https://github.com/All-The-Game-Servers/CrownsSuite/tree/master/downloads");
        if (packs == null) {
            sender.sendMessage("Resource pack: service offline");
        } else {
            sender.sendMessage("Resource pack: " + packs.getVersion() + " | " + (packs.getDownloadUrl().isBlank() ? "<not configured>" : packs.getDownloadUrl()));
            sender.sendMessage("Resource pack SHA1: " + (packs.getSha1().isBlank() ? "<not configured>" : packs.getSha1()));
            sender.sendMessage("Server copy: " + packs.getLocalPackPath());
        }
        for (ModuleHealth health : CrownsAPI.getModuleHealth()) {
            ModuleDescriptor descriptor = health.descriptor();
            sender.sendMessage("- " + descriptor.pluginName() + "-" + descriptor.version() + ".jar [" + health.state() + "]");
        }
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
            this.sendPackStatus(sender, packs);
            return true;
        }
        if (action.equals("status")) {
            this.sendPackStatus(sender, packs);
            return true;
        }
        if (action.equals("refresh")) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("You do not have permission to refresh the resource pack from GitHub Releases.");
                return true;
            }
            packs.refreshFromGitHubRelease(sender);
            return true;
        }
        if (action.equals("apply")) {
            if (!sender.hasPermission("crowns.api.admin")) {
                sender.sendMessage("You do not have permission to apply the resource pack.");
                return true;
            }
            packs.applyRequiredPack(sender);
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
            packs.applyRequiredPack(sender);
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

        sender.sendMessage("Usage: /" + label + " pack [refresh|download|apply|status|prompt|info|link|broadcast|share <player>]");
        return true;
    }

    private void sendPackStatus(CommandSender sender, ResourcePackService packs) {
        sender.sendMessage("Crowns Suite Resource Pack");
        sender.sendMessage("Enabled: " + packs.isEnabled());
        sender.sendMessage("Required: " + packs.isRequired());
        sender.sendMessage("Version: " + packs.getVersion());
        sender.sendMessage("URL: " + (packs.getUrl().isBlank() ? "<not configured>" : packs.getUrl()));
        sender.sendMessage("Download URL: " + (packs.getDownloadUrl().isBlank() ? "<not configured>" : packs.getDownloadUrl()));
        sender.sendMessage("SHA1: " + (packs.getSha1().isBlank() ? "<not configured>" : packs.getSha1()));
        sender.sendMessage("GitHub: " + packs.getGitHubPageUrl());
        sender.sendMessage("Release Source: " + packs.getGitHubOwner() + "/" + packs.getGitHubRepo() + " @ " + packs.getGitHubReleaseTag());
        sender.sendMessage("Asset Pattern: " + packs.getZipAssetPattern() + " | " + packs.getSha1AssetPattern());
        sender.sendMessage("Local Path: " + packs.getLocalPackPath());
        sender.sendMessage("ValhallaMMO Present: " + packs.isValhallaPresent());
        if (packs.isValhallaPresent()) {
            sender.sendMessage("Warning: CrownsAPI is the required pack owner. Disable ValhallaMMO's separate pack prompt or publish a merged pack.");
        }
        if (!packs.getPromptBlockReason().isBlank()) {
            sender.sendMessage("Prompt Status: " + packs.getPromptBlockReason());
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("CrownsAPI");
        sender.sendMessage("/" + label + " pack - download the current Crowns Suite resource pack onto the server");
        sender.sendMessage("/" + label + " pack download - same as /" + label + " pack");
        sender.sendMessage("/" + label + " pack refresh - resolve/download the latest pack from GitHub Releases");
        sender.sendMessage("/" + label + " pack apply - send the required pack to all online players");
        sender.sendMessage("/" + label + " pack status - show configured pack details");
        sender.sendMessage("/" + label + " pack prompt - send yourself the direct pack prompt");
        sender.sendMessage("/" + label + " pack link - show the manual install link");
        sender.sendMessage("/" + label + " pack info - show configured pack details");
        sender.sendMessage("/" + label + " gui - open the Crowns Suite home");
        sender.sendMessage("/" + label + " status - open the suite module status page");
        sender.sendMessage("/" + label + " modules - show installed module versions and health");
        sender.sendMessage("/" + label + " downloads - show current jar/resource-pack download metadata");
        if (sender.hasPermission("crowns.api.admin")) {
            sender.sendMessage("/" + label + " pack share <player> - send the pack prompt to a player");
            sender.sendMessage("/" + label + " pack broadcast - send the pack prompt to everyone online");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("pack", "gui", "status", "modules", "downloads", "help"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pack")) {
            List<String> base = new ArrayList<>(List.of("download", "refresh", "apply", "status", "prompt", "info", "link"));
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
