package com.xkstudios.crowns.drugs.gui;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.drugs.DrugBusiness;
import com.xkstudios.crowns.drugs.DrugManager;
import com.xkstudios.crowns.drugs.DrugProduct;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public class DrugMenuManager {
    private final CrownsPlugin plugin;

    public DrugMenuManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHub(Player player) {
        DrugManager manager = this.plugin.getDrugManager();
        manager.migrateLegacyStock(player);
        DrugBusiness business = manager.getBusiness(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("drugs-hub", 54, Component.text("Crowns Drugs", NamedTextColor.DARK_RED));
        inventory.setItem(10, CrownsAPI.getSuiteGui().button(Material.MOSS_BLOCK, "Grow", NamedTextColor.GREEN, List.of(
                Component.text("Seeds: " + business.seeds(), NamedTextColor.GRAY),
                Component.text("Grow raw stock into physical items.", NamedTextColor.GRAY)
        ), "drugs:open:grow", "lowlight/drugs/grow"));
        inventory.setItem(12, CrownsAPI.getSuiteGui().button(Material.SMOKER, "Process", NamedTextColor.AQUA, List.of(
                Component.text("Supplies: " + business.supplies(), NamedTextColor.GRAY),
                Component.text("Turn raw stock into consumable packages.", NamedTextColor.GRAY)
        ), "drugs:open:process", "lowlight/drugs/process"));
        inventory.setItem(14, CrownsAPI.getSuiteGui().button(Material.POTION, "Use", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Use packaged items by right-clicking them.", NamedTextColor.GRAY),
                Component.text("Open for effect details.", NamedTextColor.GRAY)
        ), "drugs:open:use", "lowlight/drugs/use"));
        inventory.setItem(16, CrownsAPI.getSuiteGui().button(Material.EMERALD, "Sell", NamedTextColor.GOLD, List.of(
                Component.text("Sell packaged stock directly for Crowns.", NamedTextColor.GRAY)
        ), "drugs:open:sell", "lowlight/drugs/sell"));
        inventory.setItem(29, CrownsAPI.getSuiteGui().button(Material.ANVIL, "Upgrades", NamedTextColor.YELLOW, List.of(
                Component.text("Lab tier: " + business.labTier(), NamedTextColor.GRAY),
                Component.text("Storage tier: " + business.storageTier(), NamedTextColor.GRAY),
                Component.text("Processor tier: " + business.processorTier(), NamedTextColor.GRAY)
        ), "drugs:open:upgrades", "lowlight/drugs/upgrades"));
        inventory.setItem(31, CrownsAPI.getSuiteGui().button(Material.CHEST, "Storage", NamedTextColor.WHITE, List.of(
                Component.text("Raw stock: " + manager.rawStock(player), NamedTextColor.GRAY),
                Component.text("Packaged stock: " + manager.packagedStock(player), NamedTextColor.GRAY)
        ), "drugs:open:storage", "lowlight/drugs/storage"));
        inventory.setItem(33, CrownsAPI.getSuiteGui().info(Material.GOLD_INGOT, "Crowns Economy", NamedTextColor.GOLD, List.of(
                Component.text("Drug sales pay straight into your Crowns balance.", NamedTextColor.GRAY),
                Component.text("Restocks and upgrades also cost Crowns.", NamedTextColor.GRAY)
        ), "lowlight/drugs/crowns_economy"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().backToHomeButton());
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGrowMenu(Player player) {
        DrugManager manager = this.plugin.getDrugManager();
        DrugBusiness business = manager.getBusiness(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("drugs-grow", 54, Component.text("Grow Stock", NamedTextColor.GREEN));
        inventory.setItem(10, this.productCard(manager, player, business, DrugProduct.MARIJUANA, "drugs:grow:marijuana"));
        inventory.setItem(13, this.productCard(manager, player, business, DrugProduct.COCAINE, "drugs:grow:cocaine"));
        inventory.setItem(16, this.productCard(manager, player, business, DrugProduct.METH, "drugs:grow:meth"));
        inventory.setItem(31, CrownsAPI.getSuiteGui().button(Material.WHEAT_SEEDS, "Restock Seeds", NamedTextColor.GREEN, List.of(
                Component.text("Current: " + business.seeds(), NamedTextColor.GRAY),
                Component.text("Cost: " + manager.formatCrowns(this.plugin.getConfig().getLong("drugs.seeds-restock-cost", 120L)), NamedTextColor.YELLOW)
        ), "drugs:restock:seeds", "lowlight/drugs/restock_seeds"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openProcessMenu(Player player) {
        DrugManager manager = this.plugin.getDrugManager();
        DrugBusiness business = manager.getBusiness(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("drugs-process", 54, Component.text("Process Stock", NamedTextColor.AQUA));
        inventory.setItem(10, this.processCard(manager, player, DrugProduct.MARIJUANA));
        inventory.setItem(13, this.processCard(manager, player, DrugProduct.COCAINE));
        inventory.setItem(16, this.processCard(manager, player, DrugProduct.METH));
        inventory.setItem(31, CrownsAPI.getSuiteGui().button(Material.BLAZE_POWDER, "Restock Supplies", NamedTextColor.YELLOW, List.of(
                Component.text("Current: " + business.supplies(), NamedTextColor.GRAY),
                Component.text("Cost: " + manager.formatCrowns(this.plugin.getConfig().getLong("drugs.supplies-restock-cost", 180L)), NamedTextColor.YELLOW)
        ), "drugs:restock:supplies", "lowlight/drugs/restock_supplies"));
        inventory.setItem(33, CrownsAPI.getSuiteGui().button(Material.SMOKER, "Process All", NamedTextColor.AQUA, List.of(
                Component.text("Process every product you currently can.", NamedTextColor.GRAY)
        ), "drugs:process:all", "lowlight/drugs/process"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openUseMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("drugs-use", 54, Component.text("Use Stock", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(10, this.useCard(DrugProduct.MARIJUANA, "Night vision and a short regen burst."));
        inventory.setItem(13, this.useCard(DrugProduct.COCAINE, "Speed and haste for quick movement."));
        inventory.setItem(16, this.useCard(DrugProduct.METH, "Big speed spike with strength."));
        inventory.setItem(31, CrownsAPI.getSuiteGui().info(Material.POTION, "How To Use", NamedTextColor.WHITE, List.of(
                Component.text("Hold a packaged drug item and right-click.", NamedTextColor.GRAY),
                Component.text("Only packaged stock can be consumed.", NamedTextColor.GRAY)
        ), "lowlight/drugs/use"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openSellMenu(Player player) {
        DrugManager manager = this.plugin.getDrugManager();
        Inventory inventory = CrownsMenuHolder.create("drugs-sell", 54, Component.text("Black Market Buyers", NamedTextColor.GOLD));
        inventory.setItem(10, this.sellCard(manager, player, DrugProduct.MARIJUANA));
        inventory.setItem(13, this.sellCard(manager, player, DrugProduct.COCAINE));
        inventory.setItem(16, this.sellCard(manager, player, DrugProduct.METH));
        inventory.setItem(31, CrownsAPI.getSuiteGui().button(Material.EMERALD, "Sell All", NamedTextColor.GOLD, List.of(
                Component.text("Move every packaged batch you can this cycle.", NamedTextColor.GRAY)
        ), "drugs:sell:all", "lowlight/drugs/sell"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openUpgrades(Player player) {
        DrugBusiness business = this.plugin.getDrugManager().getBusiness(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("drugs-upgrades", 54, Component.text("Cartel Upgrades", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(20, CrownsAPI.getSuiteGui().button(Material.BONE_MEAL, "Upgrade Lab", NamedTextColor.GREEN, List.of(
                Component.text("Current tier: " + business.labTier(), NamedTextColor.GRAY),
                Component.text("Cost: " + this.plugin.getDrugManager().formatCrowns(business.labTier() * 350L), NamedTextColor.YELLOW)
        ), "drugs:upgrade:lab", "lowlight/drugs/upgrade_lab"));
        inventory.setItem(22, CrownsAPI.getSuiteGui().button(Material.CHEST, "Upgrade Storage", NamedTextColor.YELLOW, List.of(
                Component.text("Current tier: " + business.storageTier(), NamedTextColor.GRAY),
                Component.text("Cost: " + this.plugin.getDrugManager().formatCrowns(business.storageTier() * 300L), NamedTextColor.YELLOW)
        ), "drugs:upgrade:storage", "lowlight/drugs/upgrade_storage"));
        inventory.setItem(24, CrownsAPI.getSuiteGui().button(Material.BLAST_FURNACE, "Upgrade Processor", NamedTextColor.AQUA, List.of(
                Component.text("Current tier: " + business.processorTier(), NamedTextColor.GRAY),
                Component.text("Cost: " + this.plugin.getDrugManager().formatCrowns(business.processorTier() * 325L), NamedTextColor.YELLOW)
        ), "drugs:upgrade:processor", "lowlight/drugs/upgrade_processor"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openStorageMenu(Player player) {
        DrugManager manager = this.plugin.getDrugManager();
        DrugBusiness business = manager.getBusiness(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("drugs-storage", 54, Component.text("Operation Storage", NamedTextColor.WHITE));
        inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.CHEST, "Capacity", NamedTextColor.WHITE, List.of(
                Component.text("Raw stock used: " + manager.rawStock(player) + "/" + business.storageCap(), NamedTextColor.GRAY),
                Component.text("Packaged stock: " + manager.packagedStock(player), NamedTextColor.GRAY)
        ), "lowlight/drugs/storage_capacity"));
        inventory.setItem(20, this.storageCard(manager, player, DrugProduct.MARIJUANA));
        inventory.setItem(22, this.storageCard(manager, player, DrugProduct.COCAINE));
        inventory.setItem(24, this.storageCard(manager, player, DrugProduct.METH));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "drugs:hub", "lowlight/suite/nav_back"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    private org.bukkit.inventory.ItemStack productCard(DrugManager manager, Player player, DrugBusiness business, DrugProduct product, String action) {
        return CrownsAPI.getSuiteGui().button(product.rawMaterial(), product.display(), NamedTextColor.RED, List.of(
                Component.text("Seeds on hand: " + business.seeds(), NamedTextColor.GRAY),
                Component.text("Storage used: " + manager.rawStock(player) + "/" + business.storageCap(), NamedTextColor.GRAY),
                Component.text("Click to grow more.", NamedTextColor.GREEN)
        ), action, "lowlight/drugs/" + product.key() + "_raw");
    }

    private org.bukkit.inventory.ItemStack processCard(DrugManager manager, Player player, DrugProduct product) {
        return CrownsAPI.getSuiteGui().button(product.packagedMaterial(), product.display(), NamedTextColor.AQUA, List.of(
                Component.text("Raw in inventory: " + manager.countItems(player, product, "raw"), NamedTextColor.GRAY),
                Component.text("Packaged in inventory: " + manager.countItems(player, product, "packaged"), NamedTextColor.GRAY),
                Component.text("Click to process a batch.", NamedTextColor.GREEN)
        ), "drugs:process:" + product.key(), "lowlight/drugs/" + product.key() + "_packaged");
    }

    private org.bukkit.inventory.ItemStack sellCard(DrugManager manager, Player player, DrugProduct product) {
        return CrownsAPI.getSuiteGui().button(product.packagedMaterial(), product.display(), NamedTextColor.GOLD, List.of(
                Component.text("Buyer wants: " + manager.currentOrderAmount(player.getUniqueId(), product), NamedTextColor.GRAY),
                Component.text("Price each: " + manager.formatCrowns(manager.currentPrice(player.getUniqueId(), product)), NamedTextColor.YELLOW),
                Component.text("Packaged in inventory: " + manager.countItems(player, product, "packaged"), NamedTextColor.GRAY)
        ), "drugs:sell:" + product.key(), "lowlight/drugs/" + product.key() + "_packaged");
    }

    private org.bukkit.inventory.ItemStack useCard(DrugProduct product, String detail) {
        return CrownsAPI.getSuiteGui().info(product.packagedMaterial(), product.display(), NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text(detail, NamedTextColor.GRAY)
        ), "lowlight/drugs/" + product.key() + "_packaged");
    }

    private org.bukkit.inventory.ItemStack storageCard(DrugManager manager, Player player, DrugProduct product) {
        return CrownsAPI.getSuiteGui().info(product.packagedMaterial(), product.display(), NamedTextColor.WHITE, List.of(
                Component.text("Raw items: " + manager.countItems(player, product, "raw"), NamedTextColor.GRAY),
                Component.text("Packaged items: " + manager.countItems(player, product, "packaged"), NamedTextColor.GRAY)
        ), "lowlight/drugs/" + product.key() + "_packaged");
    }
}
