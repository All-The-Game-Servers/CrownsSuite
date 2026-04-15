package com.xkstudios.crowns.api;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class ResourcePackService {
    private final JavaPlugin plugin;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("resource-pack.enabled", false);
    }

    public String getVersion() {
        return this.plugin.getConfig().getString("resource-pack.version", "1.2.0");
    }

    public String getUrl() {
        return this.plugin.getConfig().getString("resource-pack.url",
                "https://raw.githubusercontent.com/All-The-Game-Servers/CrownsSuite/master/downloads/CrownsSuite-ResourcePack-1.2.0.zip");
    }

    public String getSha1() {
        return this.plugin.getConfig().getString("resource-pack.sha1", "8939f46d7d8fcfacefe0bacfa47776a2f9aad645");
    }

    public String getDownloadUrl() {
        return this.plugin.getConfig().getString("resource-pack.download-url", this.getUrl());
    }

    public String getGitHubPageUrl() {
        return this.plugin.getConfig().getString("resource-pack.github-page-url",
                "https://github.com/All-The-Game-Servers/CrownsSuite/tree/master/downloads");
    }

    public String getPrompt() {
        return this.plugin.getConfig().getString("resource-pack.prompt", "Install the Crowns Suite resource pack for custom items and menus.");
    }

    public boolean allowClientPromptWithValhalla() {
        return this.plugin.getConfig().getBoolean("resource-pack.allow-client-prompt-with-valhalla", false);
    }

    public boolean isValhallaPresent() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("ValhallaMMO");
        if (plugin != null && plugin.isEnabled()) {
            return true;
        }
        plugin = Bukkit.getPluginManager().getPlugin("ValhallaMMO Premium");
        return plugin != null && plugin.isEnabled();
    }

    public boolean canShare() {
        return this.isEnabled() && !this.getUrl().isBlank();
    }

    public boolean canPromptPlayers() {
        return this.canShare() && (!this.isValhallaPresent() || this.allowClientPromptWithValhalla());
    }

    public String getPromptBlockReason() {
        if (!this.isEnabled()) {
            return "CrownsAPI resource-pack support is disabled in config.";
        }
        if (this.getUrl().isBlank()) {
            return "A resource-pack URL is not configured yet.";
        }
        if (this.isValhallaPresent() && !this.allowClientPromptWithValhalla()) {
            return "ValhallaMMO is installed, so CrownsAPI is leaving client pack prompts disabled to avoid pack conflicts.";
        }
        return "";
    }

    public Path getLocalPackPath() {
        return this.plugin.getDataFolder().toPath().resolve("resource-pack-cache")
                .resolve("CrownsSuite-ResourcePack-" + this.getVersion() + ".zip");
    }

    public boolean requestDownload(Player player) {
        if (player == null || !this.canPromptPlayers()) {
            return false;
        }
        Component prompt = Component.text(this.getPrompt(), NamedTextColor.GRAY);
        String sha1 = this.getSha1();
        if (!sha1.isBlank()) {
            player.setResourcePack(this.getUrl(), sha1, false, prompt);
        } else {
            player.setResourcePack(this.getUrl(), "", false, prompt);
        }
        player.sendMessage(Component.text("Sent the Crowns Suite resource-pack prompt.", NamedTextColor.GREEN));
        return true;
    }

    public void downloadPackToServer(CommandSender sender) {
        if (!this.isEnabled()) {
            sender.sendMessage("CrownsAPI resource-pack support is disabled in config.");
            return;
        }
        String downloadUrl = this.getDownloadUrl();
        if (downloadUrl.isBlank()) {
            sender.sendMessage("A pack download URL is not configured yet.");
            return;
        }
        Path target = this.getLocalPackPath();
        sender.sendMessage("Downloading Crowns Suite resource pack to " + target + " ...");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                Files.createDirectories(target.getParent());
                try (InputStream input = URI.create(downloadUrl).toURL().openStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                long size = Files.size(target);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    sender.sendMessage("Downloaded Crowns Suite resource pack to " + target + " (" + size + " bytes).");
                    if (this.isValhallaPresent()) {
                        sender.sendMessage("ValhallaMMO is installed. CrownsAPI downloaded the pack locally but did not auto-prompt players, so the packs do not fight each other.");
                    }
                    CrownsAPI.publishAlert("api", "Resource Pack Downloaded",
                            "Crowns Suite resource pack " + this.getVersion() + " was downloaded to the server cache.",
                            null, false);
                });
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to download resource pack: " + exception.getMessage());
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        sender.sendMessage("Failed to download the Crowns Suite resource pack: " + exception.getMessage()));
            }
        });
    }

    public void sendManualInstall(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(Component.text("Crowns Suite Resource Pack", NamedTextColor.GOLD));
        player.sendMessage(Component.text(this.getPrompt(), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Version: " + this.getVersion(), NamedTextColor.YELLOW));
        if (this.getUrl().isBlank()) {
            player.sendMessage(Component.text("A resource-pack URL is not configured yet.", NamedTextColor.RED));
            return;
        }
        Component link = Component.text("Open resource-pack download", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(this.getUrl()));
        player.sendMessage(link);
        if (!this.getSha1().isBlank()) {
            player.sendMessage(Component.text("SHA1: " + this.getSha1(), NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(Component.text("GitHub: " + this.getGitHubPageUrl(), NamedTextColor.DARK_GRAY));
    }

    public boolean shareDownload(Player target, Player actor) {
        if (target == null || !this.requestDownload(target)) {
            return false;
        }
        if (actor != null && !actor.getUniqueId().equals(target.getUniqueId())) {
            actor.sendMessage(Component.text("Sent the resource-pack prompt to " + target.getName() + ".", NamedTextColor.GREEN));
        }
        CrownsAPI.publishAlert("api", "Resource Pack Shared",
                actor == null
                        ? "A resource-pack prompt was sent to " + target.getName() + "."
                        : actor.getName() + " sent a resource-pack prompt to " + target.getName() + ".",
                target.getUniqueId(), false);
        return true;
    }

    public void broadcastDownloadRequest() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.requestDownload(player);
        }
        CrownsAPI.publishAlert("api", "Resource Pack Prompt",
                "Crowns Suite resource-pack prompts were sent to all online players.",
                null, false);
    }

    public void broadcastManualInstall() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.sendManualInstall(player);
        }
        CrownsAPI.publishAlert("api", "Resource Pack Reminder",
                "Crowns Suite resource-pack details were broadcast to all online players.",
                null, false);
    }

    public String getStatusSummary() {
        if (!this.isEnabled()) {
            return "Manual pack sharing is currently disabled.";
        }
        if (this.isValhallaPresent() && !this.allowClientPromptWithValhalla()) {
            return "Pack download is ready, but player prompts are paused to avoid conflicts with ValhallaMMO.";
        }
        if (this.getUrl().isBlank()) {
            return "Pack sharing is enabled, but the download URL is not configured.";
        }
        return "Version " + this.getVersion() + " is ready to share manually.";
    }

    public void updateConfig(String url, String sha1, String version) {
        FileConfiguration config = this.plugin.getConfig();
        config.set("resource-pack.url", url);
        config.set("resource-pack.download-url", url);
        config.set("resource-pack.sha1", sha1);
        config.set("resource-pack.version", version);
        this.plugin.saveConfig();
    }
}
