package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class MagicPlayerListener implements Listener {
    private final CrownsMagicPlugin plugin;

    public MagicPlayerListener(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.plugin.profiles().get(event.getPlayer().getUniqueId());
        this.plugin.profiles().bindDefaults(event.getPlayer().getUniqueId());
        if (CrownsAPI.getResourceMeterService() != null) {
            CrownsAPI.getResourceMeterService().restore("magic:mana", event.getPlayer().getUniqueId(), 1000);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.profiles().save(this.plugin.profiles().get(event.getPlayer().getUniqueId()));
        if (CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().clearBindings(event.getPlayer().getUniqueId());
        }
        if (CrownsAPI.getCooldownService() != null) {
            CrownsAPI.getCooldownService().clear(event.getPlayer().getUniqueId());
        }
    }
}
