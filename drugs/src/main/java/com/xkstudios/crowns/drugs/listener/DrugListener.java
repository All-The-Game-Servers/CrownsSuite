package com.xkstudios.crowns.drugs.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.drugs.DrugProduct;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class DrugListener implements Listener {
    private final CrownsPlugin plugin;

    public DrugListener(CrownsPlugin plugin) {
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
        if (action == null || !action.startsWith("drugs:")) {
            return;
        }
        event.setCancelled(true);
        if (action.equals("drugs:hub")) {
            this.plugin.getMenuManager().openHub(player);
            return;
        }
        if (action.equals("drugs:restock:seeds")) {
            player.sendMessage(Component.text(this.plugin.getDrugManager().restockSeeds(player), NamedTextColor.GREEN));
            this.plugin.getMenuManager().openGrowMenu(player);
            return;
        }
        if (action.equals("drugs:restock:supplies")) {
            player.sendMessage(Component.text(this.plugin.getDrugManager().restockSupplies(player), NamedTextColor.GREEN));
            this.plugin.getMenuManager().openProcessMenu(player);
            return;
        }
        if (action.equals("drugs:process:all")) {
            player.sendMessage(Component.text(this.plugin.getDrugManager().processAll(player), NamedTextColor.GREEN));
            this.plugin.getMenuManager().openProcessMenu(player);
            return;
        }
        if (action.equals("drugs:sell:all")) {
            player.sendMessage(Component.text(this.plugin.getDrugManager().sellAll(player), NamedTextColor.GREEN));
            this.plugin.getMenuManager().openSellMenu(player);
            return;
        }
        if (action.equals("drugs:open:upgrades")) {
            this.plugin.getMenuManager().openUpgrades(player);
            return;
        }
        if (action.startsWith("drugs:open:")) {
            switch (action.substring("drugs:open:".length())) {
                case "grow" -> this.plugin.getMenuManager().openGrowMenu(player);
                case "process" -> this.plugin.getMenuManager().openProcessMenu(player);
                case "use" -> this.plugin.getMenuManager().openUseMenu(player);
                case "sell" -> this.plugin.getMenuManager().openSellMenu(player);
                case "upgrades" -> this.plugin.getMenuManager().openUpgrades(player);
                case "recipes" -> this.plugin.getMenuManager().openRecipesMenu(player);
                case "storage" -> this.plugin.getMenuManager().openStorageMenu(player);
            }
            return;
        }
        if (action.startsWith("drugs:grow:")) {
            DrugProduct product = DrugProduct.fromKey(action.substring("drugs:grow:".length()));
            if (product != null) {
                player.sendMessage(Component.text(this.plugin.getDrugManager().grow(player, product), NamedTextColor.GREEN));
                this.plugin.getMenuManager().openGrowMenu(player);
            }
            return;
        }
        if (action.startsWith("drugs:process:")) {
            DrugProduct product = DrugProduct.fromKey(action.substring("drugs:process:".length()));
            if (product != null) {
                player.sendMessage(Component.text(this.plugin.getDrugManager().process(player, product), NamedTextColor.GREEN));
                this.plugin.getMenuManager().openProcessMenu(player);
            }
            return;
        }
        if (action.startsWith("drugs:sell:")) {
            DrugProduct product = DrugProduct.fromKey(action.substring("drugs:sell:".length()));
            if (product != null) {
                player.sendMessage(Component.text(this.plugin.getDrugManager().sell(player, product), NamedTextColor.GREEN));
                this.plugin.getMenuManager().openSellMenu(player);
            }
            return;
        }
        if (action.startsWith("drugs:upgrade:")) {
            player.sendMessage(Component.text(this.plugin.getDrugManager().upgrade(player, action.substring("drugs:upgrade:".length())), NamedTextColor.GREEN));
            this.plugin.getMenuManager().openUpgrades(player);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        String result = this.plugin.getDrugManager().consume(player, item);
        if (result == null) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text(result, NamedTextColor.LIGHT_PURPLE));
    }
}
