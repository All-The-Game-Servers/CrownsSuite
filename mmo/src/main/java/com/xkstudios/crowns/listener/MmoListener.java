package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.mmo.MmoManager;
import com.xkstudios.crowns.mmo.MmoPerkNode;
import com.xkstudios.crowns.mmo.MmoSkill;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;

public class MmoListener implements Listener {
    private final CrownsPlugin plugin;

    public MmoListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.getConfig().getBoolean("mmo.onboarding.start-message-on-join", true)) {
            player.sendMessage(Component.text("CrownsMMO has relaunched. Use /cmmo start to begin at First Haven.", NamedTextColor.GOLD));
        }
        if (!this.plugin.getConfig().getBoolean("mmo.onboarding.teleport-on-first-join", true)) {
            return;
        }
        if (this.plugin.getMmoManager().hasWorldProgress(player.getUniqueId(), "onboarding:first_haven_start")) {
            return;
        }
        if (this.plugin.getMmoManager().getTotalLevel(player.getUniqueId(), player.getName()) > this.plugin.getMmoManager().getSkills().size()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline() && !this.plugin.getMmoManager().hasWorldProgress(player.getUniqueId(), "onboarding:first_haven_start")) {
                this.plugin.getFloorManager().startPlayer(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
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
        if (action == null || !action.startsWith("mmo:")) {
            return;
        }
        event.setCancelled(true);
        if (action.equals("mmo:hub")) {
            this.plugin.getMenuManager().openHub(player);
            return;
        }
        if (action.startsWith("mmo:open:")) {
            String target = action.substring("mmo:open:".length());
            if (target.startsWith("floor-quests:")) {
                try {
                    this.plugin.getMenuManager().openFloorQuests(player, Integer.parseInt(target.substring("floor-quests:".length())));
                } catch (NumberFormatException ignored) {
                    this.plugin.getMenuManager().openQuests(player);
                }
                return;
            }
            switch (target) {
                case "skills" -> this.plugin.getMenuManager().openSkills(player);
                case "party" -> this.plugin.getMenuManager().openParty(player);
                case "guild" -> this.plugin.getMenuManager().openGuild(player);
                case "professions" -> this.plugin.getMenuManager().openProfessions(player);
                case "combat" -> this.plugin.getMenuManager().openCombat(player);
                case "world" -> this.plugin.getMenuManager().openWorld(player);
                case "floors" -> this.plugin.getMenuManager().openFloors(player);
                case "resources" -> this.plugin.getMenuManager().openResources(player);
                case "gear" -> this.plugin.getMenuManager().openGear(player);
                case "recipes" -> this.plugin.getMenuManager().openRecipes(player);
                case "actives" -> this.plugin.getMenuManager().openActives(player);
                case "quests" -> this.plugin.getMenuManager().openQuests(player);
                case "available-quests" -> this.plugin.getMenuManager().openAvailableQuests(player);
                case "active-quests" -> this.plugin.getMenuManager().openActiveQuests(player);
                case "completed-quests" -> this.plugin.getMenuManager().openCompletedQuests(player);
                case "first-haven-path" -> this.plugin.getMenuManager().openFirstHavenPath(player);
                case "guide" -> this.plugin.getMenuManager().openGuide(player);
            }
            return;
        }
        if (action.startsWith("mmo:quest:turnin:")) {
            String questKey = action.substring("mmo:quest:turnin:".length());
            var quest = this.plugin.getQuestManager().getQuest(questKey);
            if (quest != null && !this.plugin.getQuestManager().tryTurnIn(player, quest)) {
                player.sendMessage(Component.text("You do not have the required turn-in items yet.", NamedTextColor.RED));
            }
            this.plugin.getMenuManager().openQuestDetail(player, questKey);
            return;
        }
        if (action.startsWith("mmo:quest:")) {
            this.plugin.getMenuManager().openQuestDetail(player, action.substring("mmo:quest:".length()));
            return;
        }
        if (action.startsWith("mmo:floor:")) {
            try {
                int floor = Integer.parseInt(action.substring("mmo:floor:".length()));
                this.plugin.getFloorManager().teleportToFloor(player, floor);
            } catch (NumberFormatException ignored) {
                player.sendMessage(Component.text("That floor gate is invalid.", NamedTextColor.RED));
            }
            return;
        }
        if (action.startsWith("mmo:skill:")) {
            MmoSkill skill = MmoSkill.fromKey(action.substring("mmo:skill:".length()));
            if (skill != null) {
                this.plugin.getMenuManager().openSkillDetail(player, skill);
            }
            return;
        }
        if (action.startsWith("mmo:perk:")) {
            String[] parts = action.split(":", 4);
            if (parts.length == 4) {
                MmoSkill skill = MmoSkill.fromKey(parts[2]);
                if (skill != null && this.plugin.getMmoManager().unlockPerk(player, skill, parts[3])) {
                    this.plugin.getMenuManager().openSkillDetail(player, skill);
                }
            }
            return;
        }
        if (action.startsWith("mmo:active:")) {
            this.plugin.getMmoManager().activate(player, action.substring("mmo:active:".length()));
            this.plugin.getMenuManager().openActives(player);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        MmoManager manager = this.plugin.getMmoManager();
        if (this.isOre(type)) {
            manager.addXp(player, MmoSkill.MINING, this.plugin.getConfig().getLong("mmo.skills.mining.block-xp", 12L) + this.plugin.getConfig().getLong("mmo.skills.mining.ore-bonus-xp", 8L), "mine:" + type.name().toLowerCase());
            this.plugin.getQuestManager().increment(player, "gather", type.name(), 1);
            this.plugin.getItemFactory().rollResourceDrops(player, "mining", type.name(), block.getLocation());
            if (manager.maybeGrantExtraOre(player, type)) {
                block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(type == Material.ANCIENT_DEBRIS ? Material.ANCIENT_DEBRIS : this.oreDrop(type)));
            }
            return;
        }
        if (type.name().endsWith("_LOG") || type.name().endsWith("_STEM")) {
            manager.addXp(player, MmoSkill.WOODCUTTING, this.plugin.getConfig().getLong("mmo.skills.woodcutting.block-xp", 10L), "wood:" + type.name().toLowerCase());
            this.plugin.getQuestManager().increment(player, "gather", type.name(), 1);
            if (manager.maybeGrantExtraLog(player)) {
                block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(type));
            }
            return;
        }
        if (this.isHarvestReady(block)) {
            manager.addXp(player, MmoSkill.FARMING, this.plugin.getConfig().getLong("mmo.skills.farming.harvest-xp", 10L), "farm:" + type.name().toLowerCase());
            this.plugin.getQuestManager().increment(player, "gather", type.name(), 1);
            this.plugin.getItemFactory().rollResourceDrops(player, "farming", type.name(), block.getLocation());
            if (manager.maybeGrantExtraCrop(player)) {
                block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(this.cropDrop(type)));
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        this.plugin.getMmoManager().addXp(player, MmoSkill.FISHING, this.plugin.getConfig().getLong("mmo.skills.fishing.catch-xp", 18L), "fish:catch");
        this.plugin.getQuestManager().increment(player, "gather", "fishing", 1);
        this.plugin.getItemFactory().rollResourceDrops(player, "fishing", "FISHING", player.getLocation());
        if (this.plugin.getMmoManager().maybeGrantExtraFish(player)) {
            player.getInventory().addItem(new ItemStack(Material.COD));
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        this.plugin.getItemFactory().validateCrafting(event);
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        this.plugin.getItemFactory().prepareSmithing(event);
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        this.plugin.getMmoManager().addXp(player, MmoSkill.ENCHANTING, this.plugin.getConfig().getLong("mmo.skills.enchanting.use-xp", 26L), "enchant:table");
        if (this.plugin.getMmoManager().maybeRefundLapis(player)) {
            player.getInventory().addItem(new ItemStack(Material.LAPIS_LAZULI));
        }
    }

    @EventHandler
    public void onStationClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        InventoryType type = event.getView().getTopInventory().getType();
        MmoManager manager = this.plugin.getMmoManager();
        if (type == InventoryType.SMITHING && event.getRawSlot() == 3 && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
            manager.addXp(player, MmoSkill.SMITHING, this.plugin.getConfig().getLong("mmo.skills.smithing.craft-xp", 22L), "smith:" + event.getCurrentItem().getType().name().toLowerCase());
            return;
        }
        if (type == InventoryType.MERCHANT && event.getRawSlot() == 2 && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
            manager.addXp(player, MmoSkill.TRADING, this.plugin.getConfig().getLong("mmo.skills.trading.trade-xp", 18L), "trade:" + event.getCurrentItem().getType().name().toLowerCase());
            manager.maybeTradingBonus(player);
            return;
        }
        if (type == InventoryType.BREWING && event.getRawSlot() <= 2 && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
            manager.addXp(player, MmoSkill.BREWING, this.plugin.getConfig().getLong("mmo.skills.brewing.brew-xp", 20L), "brew:" + event.getCurrentItem().getType().name().toLowerCase());
            if (manager.maybeRefundBrewIngredient(player) && event.getView().getTopInventory() instanceof BrewerInventory brewerInventory) {
                ItemStack ingredient = brewerInventory.getIngredient();
                if (ingredient != null && ingredient.getType() != Material.AIR) {
                    ingredient.setAmount(Math.min(ingredient.getMaxStackSize(), ingredient.getAmount() + 1));
                    brewerInventory.setIngredient(ingredient);
                }
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player melee = event.getDamager() instanceof Player player ? player : null;
        if (melee != null) {
            Material held = melee.getInventory().getItemInMainHand().getType();
            if (held.name().endsWith("_SWORD") || held.name().endsWith("_AXE")) {
                event.setDamage(event.getDamage() * this.plugin.getMmoManager().getMeleeDamageMultiplier(melee));
                this.plugin.getMmoManager().addXp(melee, MmoSkill.SWORDSMANSHIP, this.plugin.getConfig().getLong("mmo.skills.swordsmanship.hit-xp", 5L), "sword:hit");
            }
        }
        if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player player) {
            event.setDamage(event.getDamage() * this.plugin.getMmoManager().getRangedDamageMultiplier(player));
            this.plugin.getMmoManager().addXp(player, MmoSkill.ARCHERY, this.plugin.getConfig().getLong("mmo.skills.archery.hit-xp", 6L), "archery:hit");
        }
    }

    @EventHandler
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled()) {
            return;
        }
        double reduction = this.plugin.getMmoManager().getDamageReduction(player);
        reduction += this.plugin.getItemFactory().damageReduction(player);
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            reduction += this.plugin.getItemFactory().fallReduction(player);
        }
        if (reduction > 0.0D) {
            event.setDamage(event.getDamage() * (1.0D - Math.min(0.80D, reduction)));
        }
        this.plugin.getMmoManager().addXp(player, MmoSkill.DEFENSE, this.plugin.getConfig().getLong("mmo.skills.defense.damage-xp", 6L), "defense:damage");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        this.plugin.getFloorManager().handleBossDeath(event.getEntity());
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            Material held = killer.getInventory().getItemInMainHand().getType();
            if (held.name().endsWith("_SWORD") || held.name().endsWith("_AXE")) {
                this.plugin.getMmoManager().addXp(killer, MmoSkill.SWORDSMANSHIP, this.plugin.getConfig().getLong("mmo.skills.swordsmanship.kill-xp", 18L), "sword:kill:" + event.getEntityType().name().toLowerCase());
            }
            this.plugin.getQuestManager().increment(killer, "kill", event.getEntityType().name(), 1);
            this.plugin.getItemFactory().rollResourceDrops(killer, "combat", event.getEntityType().name(), event.getEntity().getLocation());
            if (this.isBoss(event.getEntityType())) {
                this.plugin.getMmoManager().markBossKill(killer, event.getEntityType().name().toLowerCase(), this.prettyBoss(event.getEntityType()));
            }
        }
        if (event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damageByEntityEvent
                && damageByEntityEvent.getDamager() instanceof AbstractArrow arrow
                && arrow.getShooter() instanceof Player player) {
            this.plugin.getMmoManager().addXp(player, MmoSkill.ARCHERY, this.plugin.getConfig().getLong("mmo.skills.archery.kill-xp", 20L), "archery:kill:" + event.getEntityType().name().toLowerCase());
            this.plugin.getQuestManager().increment(player, "kill", event.getEntityType().name(), 1);
            this.plugin.getMmoManager().maybeRefundArrow(player);
            if (this.isBoss(event.getEntityType())) {
                this.plugin.getMmoManager().markBossKill(player, event.getEntityType().name().toLowerCase(), this.prettyBoss(event.getEntityType()));
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) {
            return;
        }
        this.plugin.getFloorManager().scaleFloorMob(event.getEntity());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getChunk().equals(event.getTo().getChunk())) {
            return;
        }
        Player player = event.getPlayer();
        String biome = event.getTo().getBlock().getBiome().name().toLowerCase();
        this.plugin.getItemFactory().applyMovementGear(player);
        this.plugin.getMmoManager().discoverBiome(player, biome);
        this.plugin.getQuestManager().handleExplore(player);
    }

    private boolean isOre(Material material) {
        return material.name().contains("ORE") || material == Material.ANCIENT_DEBRIS;
    }

    private Material oreDrop(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
            case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.RAW_IRON;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Material.RAW_GOLD;
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
            default -> material;
        };
    }

    private boolean isHarvestReady(Block block) {
        BlockState state = block.getState();
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return switch (state.getType()) {
            case SUGAR_CANE, CACTUS, MELON, PUMPKIN -> true;
            default -> false;
        };
    }

    private Material cropDrop(Material material) {
        return switch (material) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA -> Material.COCOA_BEANS;
            case MELON -> Material.MELON_SLICE;
            case PUMPKIN -> Material.PUMPKIN;
            default -> Material.WHEAT;
        };
    }

    private boolean isBoss(EntityType type) {
        return type == EntityType.ENDER_DRAGON
                || type == EntityType.WITHER
                || type == EntityType.WARDEN
                || type == EntityType.ELDER_GUARDIAN;
    }

    private String prettyBoss(EntityType type) {
        return switch (type) {
            case ENDER_DRAGON -> "Ender Dragon";
            case WITHER -> "Wither";
            case WARDEN -> "Warden";
            case ELDER_GUARDIAN -> "Elder Guardian";
            default -> type.name().toLowerCase().replace('_', ' ');
        };
    }
}
