package com.xkstudios.crowns.event;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class EventItemFactory {
    private final CrownsPlugin plugin;
    private final NamespacedKey eventKeyTag;
    private final NamespacedKey relicKeyTag;
    private final NamespacedKey relicPointsTag;
    private final NamespacedKey rarityTag;
    private final NamespacedKey rewardKeyTag;
    private final NamespacedKey craftMaterialKeyTag;
    private final NamespacedKey modelPathTag;

    public EventItemFactory(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.eventKeyTag = new NamespacedKey(plugin, "event_key");
        this.relicKeyTag = new NamespacedKey(plugin, "relic_key");
        this.relicPointsTag = new NamespacedKey(plugin, "relic_points");
        this.rarityTag = new NamespacedKey(plugin, "relic_rarity");
        this.rewardKeyTag = new NamespacedKey(plugin, "reward_key");
        this.craftMaterialKeyTag = new NamespacedKey(plugin, "craft_material_key");
        this.modelPathTag = new NamespacedKey(plugin, "model_path");
    }

    public List<RelicDefinition> getRelics() {
        return new ArrayList<>(this.relicDefinitions(this.activeEventKey()).values());
    }

    public List<CraftMaterialDefinition> getCraftMaterials() {
        return new ArrayList<>(this.craftMaterialDefinitions(this.activeEventKey()).values());
    }

    public ItemStack createRelic(String key, int amount) {
        return this.createRelic(this.activeEventKey(), key, amount);
    }

    public ItemStack createRelic(String eventKey, String key, int amount) {
        RelicDefinition definition = this.relicDefinitions(eventKey).get(key);
        if (definition == null) {
            return null;
        }
        ItemStack item = new ItemStack(this.sanitizeRelicMaterial(definition.material()), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(definition.displayName(), definition.color()));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(definition.description(), NamedTextColor.GRAY));
        lore.add(Component.text("Rarity: " + definition.rarityLabel(), definition.color()));
        lore.add(Component.text("Turn in for " + definition.points() + " point(s).", NamedTextColor.GOLD));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.eventKeyTag, PersistentDataType.STRING, eventKey);
        data.set(this.relicKeyTag, PersistentDataType.STRING, definition.key());
        data.set(this.relicPointsTag, PersistentDataType.INTEGER, definition.points());
        data.set(this.rarityTag, PersistentDataType.STRING, definition.rarity());
        data.set(this.modelPathTag, PersistentDataType.STRING, definition.modelPath());
        PackModelHelper.apply(meta, definition.modelPath());
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createRewardItem(String key) {
        return this.createRewardItem(this.activeEventKey(), key);
    }

    public ItemStack createRewardItem(String eventKey, String key) {
        RewardItemDefinition definition = this.rewardDefinitions(eventKey).get(key);
        if (definition == null) {
            return null;
        }
        ItemStack item = new ItemStack(this.sanitizeRewardMaterial(definition.material()), Math.max(1, definition.amount()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(definition.displayName(), definition.color()));
        List<Component> lore = new ArrayList<>();
        for (String line : definition.lore()) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        meta.lore(lore);
        meta.setUnbreakable(definition.unbreakable());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        for (Map.Entry<String, Integer> enchantment : definition.enchantments().entrySet()) {
            Enchantment resolved = this.resolveEnchantment(enchantment.getKey());
            if (resolved != null) {
                meta.addEnchant(resolved, enchantment.getValue(), true);
            }
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.eventKeyTag, PersistentDataType.STRING, eventKey);
        data.set(this.rewardKeyTag, PersistentDataType.STRING, definition.key());
        data.set(this.modelPathTag, PersistentDataType.STRING, definition.modelPath());
        PackModelHelper.apply(meta, definition.modelPath());
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createCraftMaterial(String key, int amount) {
        return this.createCraftMaterial(this.activeEventKey(), key, amount);
    }

    public ItemStack createCraftMaterial(String eventKey, String key, int amount) {
        CraftMaterialDefinition definition = this.craftMaterialDefinitions(eventKey).get(key);
        if (definition == null) {
            return null;
        }
        ItemStack item = new ItemStack(this.sanitizeRelicMaterial(definition.material()), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(definition.displayName(), definition.color()));
        meta.lore(List.of(
                Component.text(definition.description(), NamedTextColor.GRAY),
                Component.text("Used for event crafting and utility rewards.", NamedTextColor.AQUA)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.eventKeyTag, PersistentDataType.STRING, eventKey);
        data.set(this.craftMaterialKeyTag, PersistentDataType.STRING, definition.key());
        data.set(this.modelPathTag, PersistentDataType.STRING, definition.modelPath());
        PackModelHelper.apply(meta, definition.modelPath());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCrownsEventItem(ItemStack item) {
        return this.getItemEventKey(item) != null;
    }

    public boolean isRelic(ItemStack item) {
        return this.getRelicKey(item) != null;
    }

    public boolean isRelic(ItemStack item, String eventKey) {
        return eventKey != null && eventKey.equalsIgnoreCase(this.getItemEventKey(item)) && this.isRelic(item);
    }

    public boolean isRewardItem(ItemStack item) {
        return this.getRewardKey(item) != null;
    }

    public boolean isCraftMaterial(ItemStack item) {
        return this.getCraftMaterialKey(item) != null;
    }

    public String getItemEventKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.eventKeyTag, PersistentDataType.STRING);
    }

    public String getRelicKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.relicKeyTag, PersistentDataType.STRING);
    }

    public String getRewardKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.rewardKeyTag, PersistentDataType.STRING);
    }

    public String getCraftMaterialKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.craftMaterialKeyTag, PersistentDataType.STRING);
    }

    public int getRelicPoints(ItemStack item) {
        if (!this.isRelic(item)) {
            return 0;
        }
        Integer points = item.getItemMeta().getPersistentDataContainer().get(this.relicPointsTag, PersistentDataType.INTEGER);
        return points == null ? 0 : points;
    }

    public RelicDefinition getRelicDefinition(String key) {
        return this.relicDefinitions(this.activeEventKey()).get(key);
    }

    public RewardItemDefinition getRewardDefinition(String key) {
        return this.rewardDefinitions(this.activeEventKey()).get(key);
    }

    public CraftMaterialDefinition getCraftMaterialDefinition(String key) {
        return this.craftMaterialDefinitions(this.activeEventKey()).get(key);
    }

    private Map<String, RelicDefinition> relicDefinitions(String eventKey) {
        Map<String, RelicDefinition> defaults = EventManager.END_EVENT_KEY.equals(eventKey) ? this.defaultEndRelics() : this.defaultNetherRelics();
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection(this.eventRoot(eventKey) + ".relic-items");
        if (root == null) {
            return defaults;
        }
        Map<String, RelicDefinition> merged = new LinkedHashMap<>();
        for (RelicDefinition definition : defaults.values()) {
            ConfigurationSection section = root.getConfigurationSection(definition.key());
            Material material = section == null ? definition.material() : this.parseMaterial(section.getString("material"), definition.material());
            merged.put(definition.key(), new RelicDefinition(
                    definition.key(),
                    this.sanitizeRelicMaterial(material),
                    section == null ? definition.displayName() : section.getString("display", definition.displayName()),
                    section == null ? definition.description() : section.getString("description", definition.description()),
                    section == null ? definition.points() : Math.max(1, section.getInt("points", definition.points())),
                    section == null ? definition.rarity() : section.getString("rarity", definition.rarity()),
                    definition.color(),
                    section == null ? definition.modelPath() : section.getString("model-path", definition.modelPath())
            ));
        }
        return merged;
    }

    private Map<String, RewardItemDefinition> rewardDefinitions(String eventKey) {
        Map<String, RewardItemDefinition> defaults = EventManager.END_EVENT_KEY.equals(eventKey) ? this.defaultEndRewardItems() : this.defaultNetherRewardItems();
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection(this.eventRoot(eventKey) + ".reward-items");
        if (root == null) {
            return defaults;
        }
        Map<String, RewardItemDefinition> merged = new LinkedHashMap<>();
        for (RewardItemDefinition definition : defaults.values()) {
            ConfigurationSection section = root.getConfigurationSection(definition.key());
            Material material = section == null ? definition.material() : this.parseMaterial(section.getString("material"), definition.material());
            merged.put(definition.key(), new RewardItemDefinition(
                    definition.key(),
                    this.sanitizeRewardMaterial(material),
                    section == null ? definition.displayName() : section.getString("display", definition.displayName()),
                    section == null || section.getStringList("lore").isEmpty() ? definition.lore() : section.getStringList("lore"),
                    section == null ? definition.amount() : Math.max(1, section.getInt("amount", definition.amount())),
                    section == null ? definition.enchantments() : this.parseEnchantments(section.getConfigurationSection("enchantments"), definition.enchantments()),
                    section == null ? definition.unbreakable() : section.getBoolean("unbreakable", definition.unbreakable()),
                    definition.color(),
                    section == null ? definition.modelPath() : section.getString("model-path", definition.modelPath())
            ));
        }
        return merged;
    }

    private Map<String, CraftMaterialDefinition> craftMaterialDefinitions(String eventKey) {
        Map<String, CraftMaterialDefinition> defaults = EventManager.END_EVENT_KEY.equals(eventKey) ? this.defaultEndCraftMaterials() : Map.of();
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection(this.eventRoot(eventKey) + ".craft-materials");
        if (root == null) {
            return defaults;
        }
        Map<String, CraftMaterialDefinition> merged = new LinkedHashMap<>(defaults);
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            CraftMaterialDefinition fallback = merged.getOrDefault(key, new CraftMaterialDefinition(key, Material.STRING, this.prettyKey(key), "Event crafting material.", NamedTextColor.AQUA, "lowlight/shared/" + key));
            if (section == null) {
                continue;
            }
            merged.put(key, new CraftMaterialDefinition(
                    key,
                    this.sanitizeRelicMaterial(this.parseMaterial(section.getString("material"), fallback.material())),
                    section.getString("display", fallback.displayName()),
                    section.getString("description", fallback.description()),
                    fallback.color(),
                    section.getString("model-path", fallback.modelPath())
            ));
        }
        return merged;
    }

    private Map<String, RelicDefinition> defaultNetherRelics() {
        Map<String, RelicDefinition> defaults = new LinkedHashMap<>();
        defaults.put("ember_shard", new RelicDefinition("ember_shard", Material.FIRE_CHARGE, "Ember Shard", "A hot splinter pried from the first crack in the Nether.", 1, "common", NamedTextColor.YELLOW, "lowlight/nether/ember_shard"));
        defaults.put("gilded_fang", new RelicDefinition("gilded_fang", Material.GOLD_NUGGET, "Gilded Fang", "A jagged gold-marked trophy traded between brutal piglin clans.", 2, "uncommon", NamedTextColor.AQUA, "lowlight/nether/gilded_fang"));
        defaults.put("blaze_sigil", new RelicDefinition("blaze_sigil", Material.BLAZE_POWDER, "Blaze Sigil", "A branded ember-mark lifted from the fortress fires.", 3, "uncommon", NamedTextColor.GOLD, "lowlight/nether/blaze_sigil"));
        defaults.put("ancient_core", new RelicDefinition("ancient_core", Material.NETHERITE_SCRAP, "Ancient Core", "A dense remnant of something far older than the bastions.", 5, "rare", NamedTextColor.LIGHT_PURPLE, "lowlight/nether/ancient_core"));
        defaults.put("crown_fragment", new RelicDefinition("crown_fragment", Material.AMETHYST_SHARD, "Crown Fragment", "A legendary shard said to answer only to the boldest relic hunters.", 10, "legendary", NamedTextColor.RED, "lowlight/nether/crown_fragment"));
        return defaults;
    }

    private Map<String, RelicDefinition> defaultEndRelics() {
        Map<String, RelicDefinition> defaults = new LinkedHashMap<>();
        defaults.put("echo_shard", new RelicDefinition("echo_shard", Material.ECHO_SHARD, "Echo Shard", "A humming splinter recovered from the first tears in the void.", 1, "common", NamedTextColor.AQUA, "lowlight/end/echo_shard"));
        defaults.put("shulker_sigil", new RelicDefinition("shulker_sigil", Material.END_ROD, "Shulker Sigil", "A carved seal taken from the shattered watchposts of the End.", 2, "uncommon", NamedTextColor.LIGHT_PURPLE, "lowlight/end/shulker_sigil"));
        defaults.put("starchart_fragment", new RelicDefinition("starchart_fragment", Material.PAPER, "Starchart Fragment", "A torn survey record pointing toward forgotten islands.", 3, "uncommon", NamedTextColor.YELLOW, "lowlight/end/starchart_fragment"));
        defaults.put("void_core", new RelicDefinition("void_core", Material.AMETHYST_SHARD, "Void Core", "A dense knot of End energy recovered from unstable gateways.", 5, "rare", NamedTextColor.DARK_PURPLE, "lowlight/end/void_core"));
        defaults.put("crown_of_the_void", new RelicDefinition("crown_of_the_void", Material.FIREWORK_STAR, "Crown Of The Void", "A legendary fragment said to answer only to the deepest Endfall expeditions.", 10, "legendary", NamedTextColor.RED, "lowlight/end/crown_of_the_void"));
        return defaults;
    }

    private Map<String, RewardItemDefinition> defaultNetherRewardItems() {
        Map<String, RewardItemDefinition> defaults = new LinkedHashMap<>();
        defaults.put("scouts_ember_lantern", new RewardItemDefinition("scouts_ember_lantern", Material.SOUL_LANTERN, "Scout's Ember Lantern", List.of("A ceremonial lantern awarded to the first brave Nether scouts.", "Its flame marks Opening Week forever."), 1, Map.of(), false, NamedTextColor.GOLD, "lowlight/nether/reward/scout_lantern"));
        defaults.put("ashwalker_boots", new RewardItemDefinition("ashwalker_boots", Material.GOLDEN_BOOTS, "Ashwalker Boots", List.of("Boots tempered for the hot paths between basalt and soul sand."), 1, Map.of("FIRE_PROTECTION", 4, "SOUL_SPEED", 3, "UNBREAKING", 3), true, NamedTextColor.YELLOW, "lowlight/nether/reward/ashwalker_boots"));
        defaults.put("blazebound_bow", new RewardItemDefinition("blazebound_bow", Material.BOW, "Blazebound Bow", List.of("A bow strung in fortress fire for Opening Week champions."), 1, Map.of("POWER", 4, "FLAME", 1, "UNBREAKING", 3), true, NamedTextColor.GOLD, "lowlight/nether/reward/blazebound_bow"));
        defaults.put("bastion_guard", new RewardItemDefinition("bastion_guard", Material.SHIELD, "Bastion Guard", List.of("A scarred shield cut from piglin-forged plating."), 1, Map.of("UNBREAKING", 3, "MENDING", 1), true, NamedTextColor.AQUA, "lowlight/nether/reward/bastion_guard"));
        defaults.put("crown_of_cinders", new RewardItemDefinition("crown_of_cinders", Material.GOLDEN_HELMET, "Crown of Cinders", List.of("A ceremonial helm for the hottest pathfinders of the season."), 1, Map.of("FIRE_PROTECTION", 4, "UNBREAKING", 3, "MENDING", 1), true, NamedTextColor.RED, "lowlight/nether/reward/crown_of_cinders"));
        defaults.put("nether_opening_trophy", new RewardItemDefinition("nether_opening_trophy", Material.FIREWORK_STAR, "Nether Opening Trophy", List.of("A permanent Lowlight SMP keepsake from Nether Opening Week.", "Awarded for finishing atop the relic board."), 1, Map.of(), false, NamedTextColor.LIGHT_PURPLE, "lowlight/nether/reward/opening_trophy"));
        return defaults;
    }

    private Map<String, RewardItemDefinition> defaultEndRewardItems() {
        Map<String, RewardItemDefinition> defaults = new LinkedHashMap<>();
        defaults.put("starchart_compass", new RewardItemDefinition("starchart_compass", Material.COMPASS, "Starchart Compass", List.of("A surveyor's compass issued for the first Endfall scouts."), 1, Map.of("UNBREAKING", 3), true, NamedTextColor.AQUA, "lowlight/end/reward/starchart_compass"));
        defaults.put("voidwalker_boots", new RewardItemDefinition("voidwalker_boots", Material.CHAINMAIL_BOOTS, "Voidwalker Boots", List.of("Boots tuned for the long drops and fractured ledges of the outer End."), 1, Map.of("FEATHER_FALLING", 4, "PROTECTION", 3, "UNBREAKING", 3), true, NamedTextColor.LIGHT_PURPLE, "lowlight/end/reward/voidwalker_boots"));
        defaults.put("gateway_lantern", new RewardItemDefinition("gateway_lantern", Material.SOUL_LANTERN, "Gateway Lantern", List.of("A soft beacon for charting safe camps between broken islands."), 1, Map.of(), false, NamedTextColor.YELLOW, "lowlight/end/reward/gateway_lantern"));
        defaults.put("chorus_satchel", new RewardItemDefinition("chorus_satchel", Material.BUNDLE, "Chorus Satchel", List.of("A compact expedition satchel stitched for long-range End surveys."), 1, Map.of(), false, NamedTextColor.GOLD, "lowlight/end/reward/chorus_satchel"));
        defaults.put("crown_beyond_stars", new RewardItemDefinition("crown_beyond_stars", Material.CHAINMAIL_HELMET, "Crown Beyond Stars", List.of("A ceremonial helm awarded to the boldest Endfall pathfinders."), 1, Map.of("PROTECTION", 4, "UNBREAKING", 3, "MENDING", 1), true, NamedTextColor.RED, "lowlight/end/reward/crown_beyond_stars"));
        defaults.put("endfall_trophy", new RewardItemDefinition("endfall_trophy", Material.FIREWORK_STAR, "Endfall Trophy", List.of("A permanent Lowlight SMP keepsake from Endfall Opening Week.", "Awarded for finishing atop the End relic board."), 1, Map.of(), false, NamedTextColor.LIGHT_PURPLE, "lowlight/end/reward/endfall_trophy"));
        return defaults;
    }

    private Map<String, CraftMaterialDefinition> defaultEndCraftMaterials() {
        Map<String, CraftMaterialDefinition> defaults = new LinkedHashMap<>();
        defaults.put("void_filament", new CraftMaterialDefinition("void_filament", Material.STRING, "Void Filament", "A thin strand of End energy useful for expedition crafting.", NamedTextColor.AQUA, "lowlight/end/material/void_filament"));
        defaults.put("chorus_weave", new CraftMaterialDefinition("chorus_weave", Material.RABBIT_HIDE, "Chorus Weave", "A stitched strip of chorus fiber prepared for specialty recipes.", NamedTextColor.YELLOW, "lowlight/end/material/chorus_weave"));
        defaults.put("gateway_residue", new CraftMaterialDefinition("gateway_residue", Material.GLOW_INK_SAC, "Gateway Residue", "A volatile residue scraped from unstable End gateways.", NamedTextColor.LIGHT_PURPLE, "lowlight/end/material/gateway_residue"));
        return defaults;
    }

    public boolean migrateInventory(Player player) {
        boolean changed = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack migrated = this.migrateUnsafeItem(contents[i]);
            if (migrated != contents[i]) {
                contents[i] = migrated;
                changed = true;
            }
        }
        player.getInventory().setContents(contents);
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack migrated = this.migrateUnsafeItem(armor[i]);
            if (migrated != armor[i]) {
                armor[i] = migrated;
                changed = true;
            }
        }
        player.getInventory().setArmorContents(armor);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack migratedOffhand = this.migrateUnsafeItem(offhand);
        if (migratedOffhand != offhand) {
            player.getInventory().setItemInOffHand(migratedOffhand);
            changed = true;
        }
        ItemStack[] ender = player.getEnderChest().getContents();
        for (int i = 0; i < ender.length; i++) {
            ItemStack migrated = this.migrateUnsafeItem(ender[i]);
            if (migrated != ender[i]) {
                ender[i] = migrated;
                changed = true;
            }
        }
        player.getEnderChest().setContents(ender);
        if (changed) {
            player.updateInventory();
        }
        return changed;
    }

    public ItemStack migrateUnsafeItem(ItemStack item) {
        if (!this.isCrownsEventItem(item) || item == null || item.getItemMeta() == null || !this.isUnsafeEventMaterial(item.getType())) {
            return item;
        }
        String eventKey = this.getItemEventKey(item);
        if (this.isRelic(item)) {
            return this.createRelic(eventKey, this.getRelicKey(item), item.getAmount());
        }
        if (this.isRewardItem(item)) {
            ItemStack rebuilt = this.createRewardItem(eventKey, this.getRewardKey(item));
            if (rebuilt != null) {
                rebuilt.setAmount(item.getAmount());
            }
            return rebuilt == null ? item : rebuilt;
        }
        if (this.isCraftMaterial(item)) {
            return this.createCraftMaterial(eventKey, this.getCraftMaterialKey(item), item.getAmount());
        }
        return item;
    }

    public boolean blocksVanillaUse(ItemStack item) {
        return this.plugin.getConfig().getBoolean("events.item-safety.enabled", true) && this.isCrownsEventItem(item);
    }

    public boolean isUnsafeEventMaterial(Material material) {
        return material != null && this.unsafeEventMaterials().contains(material);
    }

    private String activeEventKey() {
        EventManager eventManager = this.plugin.getEventManager();
        return eventManager == null ? EventManager.EVENT_KEY : eventManager.getActiveEventKey();
    }

    private String eventRoot(String eventKey) {
        return "events." + eventKey;
    }

    private Set<Material> unsafeEventMaterials() {
        Set<Material> materials = new HashSet<>();
        List<String> configured = this.plugin.getConfig().getStringList("events.item-safety.blocked-materials");
        List<String> values = configured.isEmpty() ? List.of(
                "NETHER_STAR", "END_CRYSTAL", "ELYTRA", "SHULKER_SHELL", "DRAGON_BREATH", "DRAGON_EGG",
                "ENDER_EYE", "ENDER_PEARL", "CHORUS_FRUIT", "POPPED_CHORUS_FRUIT", "BLAZE_ROD",
                "GHAST_TEAR", "NETHERITE_SCRAP", "ANCIENT_DEBRIS"
        ) : configured;
        for (String value : values) {
            Material material = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    private Material sanitizeRelicMaterial(Material material) {
        if (!this.isUnsafeEventMaterial(material)) {
            return material;
        }
        return this.parseMaterial(this.plugin.getConfig().getString("events.item-safety.relic-fallback-material"), Material.AMETHYST_SHARD);
    }

    private Material sanitizeRewardMaterial(Material material) {
        if (!this.isUnsafeEventMaterial(material)) {
            return material;
        }
        return this.parseMaterial(this.plugin.getConfig().getString("events.item-safety.reward-fallback-material"), Material.FIREWORK_STAR);
    }

    private Map<String, Integer> parseEnchantments(ConfigurationSection section, Map<String, Integer> fallback) {
        if (section == null) {
            return fallback;
        }
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            enchantments.put(key, section.getInt(key));
        }
        return enchantments.isEmpty() ? fallback : enchantments;
    }

    private Material parseMaterial(String value, Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
        return parsed == null ? fallback : parsed;
    }

    private String prettyKey(String key) {
        String[] parts = key.replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private Enchantment resolveEnchantment(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Enchantment enchantment = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
        if (enchantment != null) {
            return enchantment;
        }
        return Enchantment.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
    }

    public record RelicDefinition(String key, Material material, String displayName, String description, int points,
                                  String rarity, NamedTextColor color, String modelPath) {
        public String rarityLabel() {
            return this.rarity.replace('_', ' ');
        }
    }

    public record RewardItemDefinition(String key, Material material, String displayName, List<String> lore, int amount,
                                       Map<String, Integer> enchantments, boolean unbreakable, NamedTextColor color,
                                       String modelPath) {
    }

    public record CraftMaterialDefinition(String key, Material material, String displayName, String description,
                                          NamedTextColor color, String modelPath) {
    }
}
