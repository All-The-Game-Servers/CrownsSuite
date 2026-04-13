package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class EventGuiListener implements Listener {
    private final CrownsPlugin plugin;

    public EventGuiListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        if (event.getClickedInventory() == null || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        String action = CrownsAPI.getSuiteGui().readAction(item);
        if (action == null || !action.startsWith("events:")) {
            return;
        }
        event.setCancelled(true);
        switch (action) {
            case "events:hub", "events:selector" -> this.plugin.getMenuManager().openHub(player);
            case "events:turnin:hand" -> {
                player.closeInventory();
                this.plugin.getEventManager().turnInHand(player);
            }
            case "events:turnin:all" -> {
                player.closeInventory();
                this.plugin.getEventManager().turnInInventory(player);
            }
            default -> {
                if (action.startsWith("events:view:")) {
                    this.plugin.getMenuManager().openEventHub(player, action.substring("events:view:".length()));
                    return;
                }
                if (action.startsWith("events:rewards:")) {
                    this.plugin.getMenuManager().openRewards(player, action.substring("events:rewards:".length()));
                    return;
                }
                if (action.startsWith("events:guide:")) {
                    this.plugin.getMenuManager().openGuide(player, action.substring("events:guide:".length()));
                    return;
                }
                if (action.startsWith("events:placeholder:")) {
                    this.plugin.getMenuManager().openPlaceholder(player);
                    return;
                }
                if (action.startsWith("events:claim:")) {
                    this.plugin.getEventManager().claimReward(player, action.substring("events:claim:".length()));
                    this.plugin.getMenuManager().openRewards(player, this.plugin.getEventManager().getActiveEventKey());
                }
            }
        }
    }
}
