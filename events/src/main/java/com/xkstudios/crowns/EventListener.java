package com.xkstudios.crowns.event;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class EventListener implements Listener {
    private static final Set<InventoryType> PROTECTED_VANILLA_USE_TYPES = Set.of(
            InventoryType.WORKBENCH,
            InventoryType.CRAFTING,
            InventoryType.SMITHING,
            InventoryType.BREWING,
            InventoryType.FURNACE,
            InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER,
            InventoryType.BEACON
    );

    private final CrownsPlugin plugin;
    private final NamespacedKey eliteKey;

    public EventListener(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.eliteKey = new NamespacedKey(plugin, "event_elite");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.plugin.getEventManager().handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.getEventManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.getEventManager().isActiveEnvironment(player.getWorld().getEnvironment())) {
            this.plugin.getEventManager().handleWorldEntry(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!this.plugin.getEventManager().isActiveEnvironment(block.getWorld().getEnvironment())) {
            return;
        }
        if (this.plugin.getEventManager().isEndEvent()) {
            if (block.getType() == Material.CHORUS_FLOWER) {
                this.plugin.getEventManager().handleResourceNode(event.getPlayer(), block.getType());
            }
            return;
        }
        if (block.getType() == Material.ANCIENT_DEBRIS) {
            this.plugin.getEventManager().handleAncientDebris(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (!this.plugin.getEventManager().isActiveEnvironment(event.getEntity().getWorld().getEnvironment())) {
            return;
        }
        if (event.getEntityType() == EntityType.ENDER_DRAGON && this.plugin.getEventManager().isEndEvent()) {
            this.plugin.getEventManager().triggerLiveMoment(
                    "dragon-fall",
                    "Dragon Fall",
                    killer == null
                            ? "The dragon has fallen. The outer islands are now open for deeper Endfall expeditions."
                            : killer.getName() + " led the first dragon takedown. The outer islands are now open for deeper Endfall expeditions.",
                    20L * 60L * 1000L,
                    killer == null ? "System" : killer.getName());
        }
        if (killer == null) {
            return;
        }
        boolean elite = event.getEntity().getPersistentDataContainer().has(this.eliteKey, PersistentDataType.BYTE);
        this.plugin.getEventManager().handleMobKill(killer, event.getEntity().getType(), elite);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!this.plugin.getEventManager().isActiveEnvironment(block.getWorld().getEnvironment())) {
            return;
        }
        Material type = block.getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL) {
            this.plugin.getEventManager().handleCache(event.getPlayer(), block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!this.plugin.getEventManager().isLive() || !this.plugin.getEventManager().isEndEvent()) {
            return;
        }
        if (event.getEntity().getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        EntityType type = event.getEntityType();
        double chance = switch (type) {
            case ENDERMAN -> 0.08;
            case SHULKER -> 0.12;
            default -> -1.0;
        };
        if (chance <= 0.0 || Math.random() > chance) {
            return;
        }
        LivingEntity entity = event.getEntity();
        entity.getPersistentDataContainer().set(this.eliteKey, PersistentDataType.BYTE, (byte) 1);
        entity.customName(net.kyori.adventure.text.Component.text("Voidbound " + this.prettyType(type), net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
        entity.setCustomNameVisible(true);
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(maxHealth.getBaseValue() * 1.5);
            entity.setHealth(Math.min(entity.getHealth() * 1.5, maxHealth.getBaseValue()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (this.blocksVanillaUse(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        for (ItemStack ingredient : event.getInventory().getContents()) {
            if (this.blocksVanillaUse(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || !PROTECTED_VANILLA_USE_TYPES.contains(top.getType())) {
            return;
        }
        if (this.blocksVanillaUse(event.getCursor()) && event.getRawSlot() < top.getSize()) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick() && this.blocksVanillaUse(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        if (event.getHotbarButton() >= 0) {
            Player player = (Player) event.getWhoClicked();
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (this.blocksVanillaUse(hotbarItem) && event.getRawSlot() < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        if (top.getType() == InventoryType.BEACON && this.blocksVanillaUse(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        if ((top.getType() == InventoryType.WORKBENCH || top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.SMITHING)
                && event.getRawSlot() == 0 && this.inventoryContainsProtectedIngredient(top)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || !PROTECTED_VANILLA_USE_TYPES.contains(top.getType())) {
            return;
        }
        if (event.getNewItems().values().stream().noneMatch(this::blocksVanillaUse)) {
            return;
        }
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (PROTECTED_VANILLA_USE_TYPES.contains(event.getDestination().getType()) && this.blocksVanillaUse(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (this.blocksVanillaUse(event.getFuel())) {
            event.setCancelled(true);
            event.setBurning(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (this.blocksVanillaUse(event.getFuel())) {
            event.setCancelled(true);
            event.setConsuming(false);
        }
    }

    private boolean blocksVanillaUse(ItemStack item) {
        return this.plugin.getEventManager().blocksVanillaUse(item);
    }

    private boolean inventoryContainsProtectedIngredient(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (this.blocksVanillaUse(item)) {
                return true;
            }
        }
        return false;
    }

    private String prettyType(EntityType type) {
        String lower = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        String[] parts = lower.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
