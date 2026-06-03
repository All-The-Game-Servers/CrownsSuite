package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class SwordPlayerListener implements Listener {
    private final CrownsSwordsPlugin plugin;

    public SwordPlayerListener(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.plugin.profiles().get(event.getPlayer().getUniqueId());
        this.plugin.profiles().bindDefaults(event.getPlayer().getUniqueId());
        if (CrownsAPI.getResourceMeterService() != null) {
            CrownsAPI.getResourceMeterService().restore("swords:stamina", event.getPlayer().getUniqueId(), 1000);
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

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getDamager() instanceof LivingEntity attacker)) {
            return;
        }
        if (this.plugin.skills().handleIncomingDamage(player, attacker)) {
            event.setDamage(Math.max(0.0D, event.getDamage() * 0.35D));
        }
    }
}
