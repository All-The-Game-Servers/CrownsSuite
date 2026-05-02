package com.xkstudios.crowns.mmo.item;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.mmo.floor.MmoFloor;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class MmoItemFactory {
    private final CrownsPlugin plugin;
    private final NamespacedKey itemKeyTag;
    private final NamespacedKey categoryTag;
    private final NamespacedKey floorTag;
    private final NamespacedKey gearEffectTag;
    private final NamespacedKey modelPathTag;
    private final Random random = new Random();
    private final Map<String, MmoItemDefinition> items = new LinkedHashMap<>();
    private final List<ResourceDrop> resourceDrops = new ArrayList<>();
    private final Map<Integer, List<LootEntry>> bossLoot = new LinkedHashMap<>();
    private final Map<String, CraftRecipe> craftRecipes = new LinkedHashMap<>();
    private final Map<String, SmithingRecipe> smithingRecipes = new LinkedHashMap<>();

    public MmoItemFactory(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.itemKeyTag = new NamespacedKey(plugin, "mmo_item_key");
        this.categoryTag = new NamespacedKey(plugin, "mmo_item_category");
        this.floorTag = new NamespacedKey(plugin, "mmo_floor");
        this.gearEffectTag = new NamespacedKey(plugin, "mmo_gear_effect");
        this.modelPathTag = new NamespacedKey(plugin, "model_path");
    }

    public void initialize() {
        this.reload();
        this.registerCraftingRecipes();
    }

    public void reload() {
        this.items.clear();
        this.resourceDrops.clear();
        this.bossLoot.clear();
        this.craftRecipes.clear();
        this.smithingRecipes.clear();
        this.seedDefaultItems();
        this.seedDefaultDrops();
        this.seedDefaultLoot();
        this.seedDefaultRecipes();
        this.mergeConfiguredItems();
        this.mergeConfiguredDrops();
        this.mergeConfiguredBossLoot();
    }

    public Collection<MmoItemDefinition> getItems() {
        return List.copyOf(this.items.values());
    }

    public List<MmoItemDefinition> getItemsByCategory(String category) {
        return this.items.values().stream()
                .filter(item -> item.category().equalsIgnoreCase(category))
                .toList();
    }

    public List<MmoItemDefinition> getItemsForFloor(int floor) {
        return this.items.values().stream()
                .filter(item -> item.floor() == floor)
                .toList();
    }

    public List<ResourceDrop> getResourceDrops(int floor) {
        return this.resourceDrops.stream()
                .filter(drop -> drop.floor() == floor)
                .toList();
    }

    public List<LootEntry> getBossLoot(int floor) {
        return this.bossLoot.getOrDefault(floor, List.of());
    }

    public Collection<CraftRecipe> getCraftRecipes() {
        return List.copyOf(this.craftRecipes.values());
    }

    public Collection<SmithingRecipe> getSmithingRecipes() {
        return List.copyOf(this.smithingRecipes.values());
    }

    public MmoItemDefinition getDefinition(String key) {
        if (key == null) {
            return null;
        }
        return this.items.get(key.toLowerCase(Locale.ROOT));
    }

    public ItemStack createItem(String key, int amount) {
        MmoItemDefinition definition = this.getDefinition(key);
        if (definition == null) {
            return null;
        }
        ItemStack item = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(definition.displayName(), definition.color()));
        List<Component> lore = new ArrayList<>();
        for (String line : definition.lore()) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        lore.add(Component.text("Floor " + definition.floor() + " " + definition.category().replace('_', ' '), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        if (definition.unbreakable()) {
            meta.setUnbreakable(true);
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.itemKeyTag, PersistentDataType.STRING, definition.key());
        data.set(this.categoryTag, PersistentDataType.STRING, definition.category());
        data.set(this.floorTag, PersistentDataType.INTEGER, definition.floor());
        if (!definition.gearEffect().isBlank()) {
            data.set(this.gearEffectTag, PersistentDataType.STRING, definition.gearEffect());
        }
        data.set(this.modelPathTag, PersistentDataType.STRING, definition.modelPath());
        PackModelHelper.apply(meta, definition.modelPath());
        item.setItemMeta(meta);
        return item;
    }

    public String getItemKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.itemKeyTag, PersistentDataType.STRING);
    }

    public boolean isMmoItem(ItemStack item) {
        return this.getItemKey(item) != null;
    }

    public boolean isItemKey(ItemStack item, String key) {
        String itemKey = this.getItemKey(item);
        return itemKey != null && itemKey.equalsIgnoreCase(key);
    }

    public boolean hasGear(Player player, String gearEffect) {
        if (player == null || gearEffect == null || gearEffect.isBlank()) {
            return false;
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (this.hasGearEffect(armor, gearEffect)) {
                return true;
            }
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (this.hasGearEffect(item, gearEffect)) {
                return true;
            }
        }
        return false;
    }

    public void rollResourceDrops(Player player, String source, String trigger, Location location) {
        if (player == null || location == null || location.getWorld() == null) {
            return;
        }
        MmoFloor floor = this.plugin.getFloorManager().getFloor(location.getWorld());
        if (floor == null) {
            return;
        }
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String normalizedTrigger = trigger == null ? "" : trigger.toUpperCase(Locale.ROOT);
        for (ResourceDrop drop : this.resourceDrops) {
            if (drop.floor() != floor.number() || !drop.source().equalsIgnoreCase(normalizedSource)) {
                continue;
            }
            if (!drop.triggers().isEmpty() && !drop.triggers().contains(normalizedTrigger)) {
                continue;
            }
            double chance = drop.chance() + this.dropBonus(player, normalizedSource);
            if (this.random.nextDouble() > chance) {
                continue;
            }
            int amount = drop.minAmount() + this.random.nextInt(Math.max(1, drop.maxAmount() - drop.minAmount() + 1));
            ItemStack item = this.createItem(drop.itemKey(), amount);
            if (item != null) {
                location.getWorld().dropItemNaturally(location, item);
                this.plugin.getQuestManager().increment(player, "gather", drop.itemKey(), amount);
            }
        }
    }

    public void awardBossLoot(Player player, MmoFloor floor) {
        if (player == null || floor == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (LootEntry entry : this.getBossLoot(floor.number())) {
            if (this.random.nextDouble() > entry.chance()) {
                continue;
            }
            int amount = entry.minAmount() + this.random.nextInt(Math.max(1, entry.maxAmount() - entry.minAmount() + 1));
            ItemStack item = this.createItem(entry.itemKey(), amount);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        for (ItemStack item : items) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        player.sendMessage(Component.text("Floor Boss loot added to your inventory.", NamedTextColor.GOLD));
    }

    public void applyMovementGear(Player player) {
        MmoFloor floor = this.plugin.getFloorManager().getFloor(player.getWorld());
        if (floor == null) {
            return;
        }
        if (this.hasGearEffect(player.getInventory().getBoots(), "pathfinder_boots")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, true, false, true));
        }
        if (this.hasGear(player, "gatebreaker_compass") && floor.number() >= 2) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 220, 0, true, false, true));
        }
    }

    public double damageReduction(Player player) {
        MmoFloor floor = this.plugin.getFloorManager().getFloor(player == null ? null : player.getWorld());
        if (floor == null) {
            return 0.0D;
        }
        double reduction = 0.0D;
        if (this.hasGearEffect(player.getInventory().getChestplate(), "wardenhide_cloak")) {
            reduction += 0.05D;
        }
        return reduction;
    }

    public double fallReduction(Player player) {
        MmoFloor floor = this.plugin.getFloorManager().getFloor(player == null ? null : player.getWorld());
        if (floor == null) {
            return 0.0D;
        }
        return this.hasGearEffect(player.getInventory().getBoots(), "pathfinder_boots") ? 0.25D : 0.0D;
    }

    public void registerCraftingRecipes() {
        for (CraftRecipe recipe : this.craftRecipes.values()) {
            NamespacedKey key = new NamespacedKey(this.plugin, "mmo_craft_" + recipe.key());
            Bukkit.removeRecipe(key);
            ItemStack result = this.createItem(recipe.resultKey(), recipe.resultAmount());
            if (result == null) {
                continue;
            }
            ShapelessRecipe shaped = new ShapelessRecipe(key, result);
            for (String ingredientKey : recipe.mmoIngredients()) {
                ItemStack ingredient = this.createItem(ingredientKey, 1);
                if (ingredient != null) {
                    shaped.addIngredient(new RecipeChoice.ExactChoice(ingredient));
                }
            }
            for (Material material : recipe.vanillaIngredients()) {
                shaped.addIngredient(material);
            }
            Bukkit.addRecipe(shaped);
        }
    }

    public void validateCrafting(PrepareItemCraftEvent event) {
        if (event.getInventory().getResult() == null) {
            return;
        }
        boolean usesMmoItem = false;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (this.isMmoItem(item)) {
                usesMmoItem = true;
                break;
            }
        }
        if (usesMmoItem && !this.isMmoItem(event.getInventory().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    public void prepareSmithing(PrepareSmithingEvent event) {
        ItemStack template = event.getInventory().getItem(0);
        ItemStack base = event.getInventory().getItem(1);
        ItemStack addition = event.getInventory().getItem(2);
        for (SmithingRecipe recipe : this.smithingRecipes.values()) {
            if (!this.isItemKey(template, recipe.templateKey())) {
                continue;
            }
            if (base == null || base.getType() != recipe.baseMaterial()) {
                continue;
            }
            if (!this.isItemKey(addition, recipe.additionKey())) {
                continue;
            }
            event.setResult(this.createItem(recipe.resultKey(), 1));
            return;
        }
        if (this.isMmoItem(template) || this.isMmoItem(base) || this.isMmoItem(addition)) {
            event.setResult(null);
        }
    }

    private boolean hasGearEffect(ItemStack item, String gearEffect) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return false;
        }
        String effect = item.getItemMeta().getPersistentDataContainer().get(this.gearEffectTag, PersistentDataType.STRING);
        return effect != null && effect.equalsIgnoreCase(gearEffect);
    }

    private double dropBonus(Player player, String source) {
        if ("mining".equals(source) && this.hasGear(player, "deep_miner_charm")) {
            return 0.04D;
        }
        if (("farming".equals(source) || "fishing".equals(source)) && this.hasGear(player, "forager_satchel")) {
            return 0.04D;
        }
        return 0.0D;
    }

    private void seedDefaultItems() {
        this.addItem("f1_skyroot_fiber", "floor_material", Material.STRING, "Skyroot Fiber", NamedTextColor.GREEN, 1, "", "lowlight/mmo/floor1/skyroot_fiber", List.of("A flexible starter fiber from Floor 1."), false);
        this.addItem("f1_gate_splinter", "floor_material", Material.AMETHYST_SHARD, "Gate Splinter", NamedTextColor.AQUA, 1, "", "lowlight/mmo/floor1/gate_splinter", List.of("A shard chipped from the first gate."), false);
        this.addItem("f1_copperleaf", "floor_material", Material.COPPER_INGOT, "Copperleaf", NamedTextColor.GOLD, 1, "", "lowlight/mmo/floor1/copperleaf", List.of("A soft floor metal used in starter trinkets."), false);
        this.addItem("f1_gatekeeper_eye", "boss_drop", Material.ENDER_EYE, "First Gatekeeper's Eye", NamedTextColor.LIGHT_PURPLE, 1, "", "lowlight/mmo/floor1/gatekeeper_eye", List.of("Boss material for early adventurer gear."), false);
        this.addItem("f1_gatekeeper_trophy", "trophy", Material.DRAGON_HEAD, "First Gate Trophy", NamedTextColor.GOLD, 1, "", "lowlight/mmo/floor1/gatekeeper_trophy", List.of("Proof you crossed the first gate."), false);
        this.addItem("f2_ironbark_plate", "floor_material", Material.IRON_NUGGET, "Ironbark Plate", NamedTextColor.GRAY, 2, "", "lowlight/mmo/floor2/ironbark_plate", List.of("Dense bark-metal from Floor 2 threats."), false);
        this.addItem("f2_deep_crystal", "floor_material", Material.PRISMARINE_CRYSTALS, "Deep Crystal", NamedTextColor.AQUA, 2, "", "lowlight/mmo/floor2/deep_crystal", List.of("A cold crystal from deeper floor stone."), false);
        this.addItem("f2_warden_thread", "gear_component", Material.PHANTOM_MEMBRANE, "Warden Thread", NamedTextColor.BLUE, 2, "", "lowlight/mmo/floor2/warden_thread", List.of("A reinforced lining for survival gear."), false);
        this.addItem("f2_gatekeeper_heart", "boss_drop", Material.ECHO_SHARD, "Second Gatekeeper's Heart", NamedTextColor.DARK_PURPLE, 2, "", "lowlight/mmo/floor2/gatekeeper_heart", List.of("Boss material for Floor 2 utility gear."), false);
        this.addItem("f2_gatekeeper_trophy", "trophy", Material.SCULK_CATALYST, "Second Gate Trophy", NamedTextColor.GOLD, 2, "", "lowlight/mmo/floor2/gatekeeper_trophy", List.of("Proof you survived the second gate."), false);
        this.addItem("f3_void_silk", "floor_material", Material.FEATHER, "Void Silk", NamedTextColor.DARK_PURPLE, 3, "", "lowlight/mmo/floor3/void_silk", List.of("Lightweight material from Floor 3 pressure."), false);
        this.addItem("f3_ancient_lumen", "floor_material", Material.GLOWSTONE_DUST, "Ancient Lumen", NamedTextColor.YELLOW, 3, "", "lowlight/mmo/floor3/ancient_lumen", List.of("A warm mote used in exploration tools."), false);
        this.addItem("f3_starmetal_flake", "gear_component", Material.IRON_NUGGET, "Starmetal Flake", NamedTextColor.WHITE, 3, "", "lowlight/mmo/floor3/starmetal_flake", List.of("A rare flake for prestige utility gear."), false);
        this.addItem("f3_gatekeeper_core", "boss_drop", Material.ECHO_SHARD, "Third Gatekeeper's Core", NamedTextColor.LIGHT_PURPLE, 3, "", "lowlight/mmo/floor3/gatekeeper_core", List.of("Boss material for Floor 3 utility gear."), false);
        this.addItem("f3_gatekeeper_trophy", "trophy", Material.RECOVERY_COMPASS, "Third Gate Trophy", NamedTextColor.GOLD, 3, "", "lowlight/mmo/floor3/gatekeeper_trophy", List.of("Proof you broke the third gate."), false);
        this.addItem("pathfinder_boots", "adventurer_gear", Material.LEATHER_BOOTS, "Pathfinder Boots", NamedTextColor.AQUA, 1, "pathfinder_boots", "lowlight/mmo/gear/pathfinder_boots", List.of("Utility gear: speed in floor worlds.", "Also softens fall damage slightly."), true);
        this.addItem("deep_miner_charm", "adventurer_gear", Material.AMETHYST_SHARD, "Deep Miner's Charm", NamedTextColor.GOLD, 1, "deep_miner_charm", "lowlight/mmo/gear/deep_miner_charm", List.of("Utility gear: improves floor mining material rolls."), false);
        this.addItem("gatebreaker_compass", "adventurer_gear", Material.COMPASS, "Gatebreaker Compass", NamedTextColor.LIGHT_PURPLE, 1, "gatebreaker_compass", "lowlight/mmo/gear/gatebreaker_compass", List.of("Utility gear: grants vision support in deeper floors."), false);
        this.addItem("forager_satchel", "adventurer_gear", Material.BUNDLE, "Forager Satchel", NamedTextColor.GREEN, 2, "forager_satchel", "lowlight/mmo/gear/forager_satchel", List.of("Utility gear: improves farming and fishing material rolls."), false);
        this.addItem("wardenhide_cloak", "adventurer_gear", Material.LEATHER_CHESTPLATE, "Wardenhide Cloak", NamedTextColor.BLUE, 2, "wardenhide_cloak", "lowlight/mmo/gear/wardenhide_cloak", List.of("Utility gear: minor floor-world damage reduction."), true);
        this.addItem("veilwalkers_lantern", "adventurer_gear", Material.SOUL_LANTERN, "Veilwalker's Lantern", NamedTextColor.DARK_PURPLE, 3, "veilwalkers_lantern", "lowlight/mmo/gear/veilwalkers_lantern", List.of("Prestige utility gear for future floor systems."), false);
    }

    private void seedDefaultDrops() {
        this.addDrop(1, "mining", "f1_copperleaf", 0.08D, 1, 1, Set.of("COPPER_ORE", "DEEPSLATE_COPPER_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE"));
        this.addDrop(1, "mining", "f1_gate_splinter", 0.04D, 1, 1, Set.of("AMETHYST_CLUSTER", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE"));
        this.addDrop(1, "farming", "f1_skyroot_fiber", 0.04D, 1, 2, Set.of());
        this.addDrop(1, "fishing", "f1_skyroot_fiber", 0.05D, 1, 1, Set.of());
        this.addDrop(1, "combat", "f1_gate_splinter", 0.03D, 1, 1, Set.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER"));
        this.addDrop(2, "mining", "f2_deep_crystal", 0.07D, 1, 1, Set.of("DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_EMERALD_ORE", "DEEPSLATE_LAPIS_ORE", "DEEPSLATE_REDSTONE_ORE"));
        this.addDrop(2, "combat", "f2_ironbark_plate", 0.06D, 1, 2, Set.of("ZOMBIE", "SKELETON", "WITHER_SKELETON", "WARDEN"));
        this.addDrop(2, "combat", "f2_warden_thread", 0.03D, 1, 1, Set.of("WARDEN", "PHANTOM", "ENDERMAN"));
        this.addDrop(2, "fishing", "f2_deep_crystal", 0.03D, 1, 1, Set.of());
        this.addDrop(3, "mining", "f3_starmetal_flake", 0.06D, 1, 1, Set.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "ANCIENT_DEBRIS"));
        this.addDrop(3, "combat", "f3_void_silk", 0.05D, 1, 2, Set.of("ENDERMAN", "SHULKER", "WARDEN"));
        this.addDrop(3, "combat", "f3_ancient_lumen", 0.03D, 1, 1, Set.of("ENDERMAN", "SHULKER", "BLAZE"));
        this.addDrop(3, "fishing", "f3_ancient_lumen", 0.02D, 1, 1, Set.of());
    }

    private void seedDefaultLoot() {
        this.addLoot(1, "f1_gatekeeper_trophy", 1.0D, 1, 1);
        this.addLoot(1, "f1_gatekeeper_eye", 1.0D, 1, 1);
        this.addLoot(1, "f1_gate_splinter", 1.0D, 2, 4);
        this.addLoot(2, "f2_gatekeeper_trophy", 1.0D, 1, 1);
        this.addLoot(2, "f2_gatekeeper_heart", 1.0D, 1, 1);
        this.addLoot(2, "f2_ironbark_plate", 1.0D, 3, 6);
        this.addLoot(2, "f2_warden_thread", 0.75D, 1, 2);
        this.addLoot(3, "f3_gatekeeper_trophy", 1.0D, 1, 1);
        this.addLoot(3, "f3_gatekeeper_core", 1.0D, 1, 1);
        this.addLoot(3, "f3_void_silk", 1.0D, 3, 6);
        this.addLoot(3, "f3_starmetal_flake", 0.80D, 1, 2);
    }

    private void seedDefaultRecipes() {
        this.craftRecipes.put("deep_miner_charm", new CraftRecipe("deep_miner_charm", "deep_miner_charm", 1, List.of("f1_copperleaf", "f1_gate_splinter"), List.of(Material.COPPER_INGOT)));
        this.craftRecipes.put("gatebreaker_compass", new CraftRecipe("gatebreaker_compass", "gatebreaker_compass", 1, List.of("f1_gatekeeper_eye", "f1_gate_splinter"), List.of(Material.COMPASS)));
        this.craftRecipes.put("forager_satchel", new CraftRecipe("forager_satchel", "forager_satchel", 1, List.of("f2_deep_crystal", "f1_skyroot_fiber", "f1_skyroot_fiber"), List.of(Material.LEATHER)));
        this.craftRecipes.put("veilwalkers_lantern", new CraftRecipe("veilwalkers_lantern", "veilwalkers_lantern", 1, List.of("f3_gatekeeper_core", "f3_ancient_lumen", "f3_starmetal_flake"), List.of(Material.SOUL_LANTERN)));
        this.smithingRecipes.put("pathfinder_boots", new SmithingRecipe("pathfinder_boots", "pathfinder_boots", "f1_gate_splinter", Material.LEATHER_BOOTS, "f1_skyroot_fiber"));
        this.smithingRecipes.put("wardenhide_cloak", new SmithingRecipe("wardenhide_cloak", "wardenhide_cloak", "f2_gatekeeper_heart", Material.LEATHER_CHESTPLATE, "f2_ironbark_plate"));
    }

    private void mergeConfiguredItems() {
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection("mmo.items");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            MmoItemDefinition fallback = this.getDefinition(key);
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Material material = this.parseMaterial(section.getString("material"), fallback == null ? Material.PAPER : fallback.material());
            String category = section.getString("category", fallback == null ? "floor_material" : fallback.category());
            String displayName = section.getString("display-name", fallback == null ? key : fallback.displayName());
            NamedTextColor color = this.parseColor(section.getString("color"), fallback == null ? NamedTextColor.WHITE : fallback.color());
            int floor = section.getInt("floor", fallback == null ? 1 : fallback.floor());
            String gearEffect = section.getString("gear-effect", fallback == null ? "" : fallback.gearEffect());
            String modelPath = section.getString("model-path", fallback == null ? "lowlight/mmo/" + key : fallback.modelPath());
            List<String> lore = section.getStringList("lore");
            if (lore.isEmpty() && fallback != null) {
                lore = fallback.lore();
            }
            boolean unbreakable = section.getBoolean("unbreakable", fallback != null && fallback.unbreakable());
            this.addItem(key, category, material, displayName, color, floor, gearEffect, modelPath, lore, unbreakable);
        }
    }

    private void mergeConfiguredDrops() {
        ConfigurationSection floors = this.plugin.getConfig().getConfigurationSection("mmo.floors.list");
        if (floors == null) {
            return;
        }
        for (String floorKey : floors.getKeys(false)) {
            int floor = this.safeInt(floorKey, -1);
            ConfigurationSection drops = floors.getConfigurationSection(floorKey + ".resource-drops");
            if (floor <= 0 || drops == null) {
                continue;
            }
            int configuredFloor = floor;
            this.resourceDrops.removeIf(drop -> drop.floor() == configuredFloor);
            for (String key : drops.getKeys(false)) {
                ConfigurationSection section = drops.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                this.addDrop(floor,
                        section.getString("source", "mining"),
                        section.getString("item", key),
                        section.getDouble("chance", 0.05D),
                        section.getInt("min", 1),
                        section.getInt("max", 1),
                        this.upperSet(section.getStringList("triggers")));
            }
        }
    }

    private void mergeConfiguredBossLoot() {
        ConfigurationSection floors = this.plugin.getConfig().getConfigurationSection("mmo.floors.list");
        if (floors == null) {
            return;
        }
        for (String floorKey : floors.getKeys(false)) {
            int floor = this.safeInt(floorKey, -1);
            ConfigurationSection loot = floors.getConfigurationSection(floorKey + ".boss.loot");
            if (floor <= 0 || loot == null) {
                continue;
            }
            this.bossLoot.remove(floor);
            for (String key : loot.getKeys(false)) {
                ConfigurationSection section = loot.getConfigurationSection(key);
                if (section == null) {
                    this.addLoot(floor, key, 1.0D, 1, 1);
                    continue;
                }
                this.addLoot(floor, section.getString("item", key), section.getDouble("chance", 1.0D), section.getInt("min", 1), section.getInt("max", 1));
            }
        }
    }

    private void addItem(String key, String category, Material material, String displayName, NamedTextColor color, int floor, String gearEffect, String modelPath, List<String> lore, boolean unbreakable) {
        this.items.put(key.toLowerCase(Locale.ROOT), new MmoItemDefinition(key.toLowerCase(Locale.ROOT), category, material, displayName, color, floor, gearEffect == null ? "" : gearEffect, modelPath, List.copyOf(lore), unbreakable));
    }

    private void addDrop(int floor, String source, String itemKey, double chance, int min, int max, Set<String> triggers) {
        this.resourceDrops.add(new ResourceDrop(floor, source.toLowerCase(Locale.ROOT), itemKey.toLowerCase(Locale.ROOT), Math.max(0.0D, Math.min(1.0D, chance)), Math.max(1, min), Math.max(min, max), Set.copyOf(triggers)));
    }

    private void addLoot(int floor, String itemKey, double chance, int min, int max) {
        this.bossLoot.computeIfAbsent(floor, ignored -> new ArrayList<>())
                .add(new LootEntry(itemKey.toLowerCase(Locale.ROOT), Math.max(0.0D, Math.min(1.0D, chance)), Math.max(1, min), Math.max(min, max)));
    }

    private Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private NamedTextColor parseColor(String raw, NamedTextColor fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        NamedTextColor color = NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT));
        return color == null ? fallback : color;
    }

    private Set<String> upperSet(List<String> raw) {
        Set<String> values = new HashSet<>();
        for (String value : raw) {
            values.add(value.toUpperCase(Locale.ROOT));
        }
        return values;
    }

    private int safeInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public record MmoItemDefinition(String key, String category, Material material, String displayName, NamedTextColor color, int floor, String gearEffect, String modelPath, List<String> lore, boolean unbreakable) {
    }

    public record ResourceDrop(int floor, String source, String itemKey, double chance, int minAmount, int maxAmount, Set<String> triggers) {
    }

    public record LootEntry(String itemKey, double chance, int minAmount, int maxAmount) {
    }

    public record CraftRecipe(String key, String resultKey, int resultAmount, List<String> mmoIngredients, List<Material> vanillaIngredients) {
    }

    public record SmithingRecipe(String key, String resultKey, String templateKey, Material baseMaterial, String additionKey) {
    }
}
