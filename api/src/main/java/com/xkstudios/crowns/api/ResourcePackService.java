package com.xkstudios.crowns.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
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
        return this.plugin.getConfig().getString("resource-pack.url", "");
    }

    public String getSha1() {
        return this.plugin.getConfig().getString("resource-pack.sha1", "");
    }

    public String getPrompt() {
        return this.plugin.getConfig().getString("resource-pack.prompt", "Install the Crowns Suite resource pack for custom items and menus.");
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
        if (this.getUrl().isBlank()) {
            return "Pack sharing is enabled, but the download URL is not configured.";
        }
        return "Version " + this.getVersion() + " is ready to share manually.";
    }

    public void updateConfig(String url, String sha1, String version) {
        FileConfiguration config = this.plugin.getConfig();
        config.set("resource-pack.url", url);
        config.set("resource-pack.sha1", sha1);
        config.set("resource-pack.version", version);
        this.plugin.saveConfig();
    }
}
