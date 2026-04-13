package com.xkstudios.crowns.drugs;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;

public enum DrugProduct {
    MARIJUANA("marijuana", "Marijuana", Material.FERN, Material.DRIED_KELP, 2, 55L,
            PotionEffectType.NIGHT_VISION, 20 * 60, 0, PotionEffectType.REGENERATION, 20 * 20, 0),
    COCAINE("cocaine", "Cocaine", Material.SUGAR_CANE, Material.SUGAR, 1, 95L,
            PotionEffectType.SPEED, 20 * 45, 1, PotionEffectType.HASTE, 20 * 45, 0),
    METH("meth", "Meth", Material.PRISMARINE_CRYSTALS, Material.LIGHT_BLUE_DYE, 1, 120L,
            PotionEffectType.SPEED, 20 * 35, 2, PotionEffectType.STRENGTH, 20 * 35, 0);

    private final String key;
    private final String display;
    private final Material rawMaterial;
    private final Material packagedMaterial;
    private final int growYield;
    private final long baseSellPrice;
    private final PotionEffectType primaryEffect;
    private final int primaryDurationTicks;
    private final int primaryAmplifier;
    private final PotionEffectType secondaryEffect;
    private final int secondaryDurationTicks;
    private final int secondaryAmplifier;

    DrugProduct(String key, String display, Material rawMaterial, Material packagedMaterial, int growYield, long baseSellPrice,
                PotionEffectType primaryEffect, int primaryDurationTicks, int primaryAmplifier,
                PotionEffectType secondaryEffect, int secondaryDurationTicks, int secondaryAmplifier) {
        this.key = key;
        this.display = display;
        this.rawMaterial = rawMaterial;
        this.packagedMaterial = packagedMaterial;
        this.growYield = growYield;
        this.baseSellPrice = baseSellPrice;
        this.primaryEffect = primaryEffect;
        this.primaryDurationTicks = primaryDurationTicks;
        this.primaryAmplifier = primaryAmplifier;
        this.secondaryEffect = secondaryEffect;
        this.secondaryDurationTicks = secondaryDurationTicks;
        this.secondaryAmplifier = secondaryAmplifier;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return packagedMaterial;
    }

    public Material rawMaterial() {
        return rawMaterial;
    }

    public Material packagedMaterial() {
        return packagedMaterial;
    }

    public int growYield() {
        return growYield;
    }

    public long baseSellPrice() {
        return baseSellPrice;
    }

    public PotionEffectType primaryEffect() {
        return primaryEffect;
    }

    public int primaryDurationTicks() {
        return primaryDurationTicks;
    }

    public int primaryAmplifier() {
        return primaryAmplifier;
    }

    public PotionEffectType secondaryEffect() {
        return secondaryEffect;
    }

    public int secondaryDurationTicks() {
        return secondaryDurationTicks;
    }

    public int secondaryAmplifier() {
        return secondaryAmplifier;
    }

    public static DrugProduct fromKey(String raw) {
        if (raw == null) {
            return null;
        }
        for (DrugProduct product : values()) {
            if (product.key.equalsIgnoreCase(raw)) {
                return product;
            }
        }
        return null;
    }

    public static List<String> keys() {
        return java.util.Arrays.stream(values()).map(DrugProduct::key).toList();
    }
}
