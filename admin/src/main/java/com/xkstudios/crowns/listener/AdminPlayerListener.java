package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class AdminPlayerListener implements Listener {
    private final CrownsPlugin plugin;

    public AdminPlayerListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.plugin.getPlaytimeManager().handleJoin(event.getPlayer());
        PlayerListener.refreshTag(this.plugin, event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.getPlaytimeManager().handleQuit(event.getPlayer());
        this.plugin.getAfkManager().clear(event.getPlayer().getUniqueId());
    }
}
