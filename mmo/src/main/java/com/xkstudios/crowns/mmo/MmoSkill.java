package com.xkstudios.crowns.mmo;

import java.util.Arrays;
import java.util.Locale;
import org.bukkit.Material;

public enum MmoSkill {
    MINING("mining", "Mining", "Gathering", Material.IRON_PICKAXE, "Break stone, ores, and deep-earth treasure."),
    WOODCUTTING("woodcutting", "Woodcutting", "Gathering", Material.IRON_AXE, "Fell trees and master forest yields."),
    FARMING("farming", "Farming", "Gathering", Material.GOLDEN_HOE, "Grow crops, harvest fields, and sustain the server."),
    FISHING("fishing", "Fishing", "Gathering", Material.FISHING_ROD, "Work the waters for rare catches and supplies."),
    SMITHING("smithing", "Smithing", "Professions", Material.ANVIL, "Forge, temper, and push gear progression upward."),
    ENCHANTING("enchanting", "Enchanting", "Professions", Material.ENCHANTING_TABLE, "Study arcane gear upgrades and enchantment flow."),
    BREWING("brewing", "Brewing", "Professions", Material.BREWING_STAND, "Refine potions, catalysts, and alchemical routines."),
    TRADING("trading", "Trading", "Professions", Material.EMERALD, "Build merchant leverage and economic reputation."),
    SWORDSMANSHIP("swordsmanship", "Swordsmanship", "Combat", Material.DIAMOND_SWORD, "Master direct melee pressure and finishing blows."),
    ARCHERY("archery", "Archery", "Combat", Material.BOW, "Control distance, precision, and ranged pressure."),
    DEFENSE("defense", "Defense", "Combat", Material.SHIELD, "Stand firm, absorb damage, and outlast danger."),
    EXPLORATION("exploration", "Exploration", "World", Material.COMPASS, "Discover biomes, conquer bosses, and climb the world arc.");

    private final String key;
    private final String displayName;
    private final String family;
    private final Material icon;
    private final String description;

    MmoSkill(String key, String displayName, String family, Material icon, String description) {
        this.key = key;
        this.displayName = displayName;
        this.family = family;
        this.icon = icon;
        this.description = description;
    }

    public String key() {
        return this.key;
    }

    public String displayName() {
        return this.displayName;
    }

    public String family() {
        return this.family;
    }

    public Material icon() {
        return this.icon;
    }

    public String description() {
        return this.description;
    }

    public static MmoSkill fromKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(skill -> skill.key.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
