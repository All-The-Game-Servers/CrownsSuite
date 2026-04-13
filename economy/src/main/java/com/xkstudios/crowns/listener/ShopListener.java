/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.block.Block
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.Action
 *  org.bukkit.event.player.PlayerInteractEvent
 */
package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.market.ChestShopData;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class ShopListener
implements Listener {
    private final CrownsPlugin plugin;

    public ShopListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        ChestShopData shop = this.plugin.getShopManager().getAt(key);
        if (shop == null) {
            return;
        }
        Player player = event.getPlayer();
        if (shop.getOwner().equals(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (player.isSneaking()) {
            String owner = Bukkit.getOfflinePlayer((UUID)shop.getOwner()).getName();
            player.sendMessage((Component)Component.text((String)("Shop by " + (owner != null ? owner : "?") + " \u2014 " + Currency.format(shop.getPrice())), (TextColor)NamedTextColor.AQUA));
            return;
        }
        if (this.plugin.getShopManager().buy(player, key)) {
            player.sendMessage((Component)Component.text((String)("Purchased for " + Currency.format(shop.getPrice())), (TextColor)NamedTextColor.GREEN));
        }
    }
}

