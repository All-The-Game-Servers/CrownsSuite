package com.xkstudios.crowns.economy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class TraderOffer {
    private final int id;
    private final ItemStack item;
    private final String displayName;
    private final long price;
    private final boolean cosmetic;
    private final String theme;
    private final String rarity;
    private final long refreshAt;

    public TraderOffer(int id, ItemStack item, String displayName, long price, boolean cosmetic, String theme, String rarity, long refreshAt) {
        this.id = id;
        this.item = item;
        this.displayName = displayName;
        this.price = price;
        this.cosmetic = cosmetic;
        this.theme = theme;
        this.rarity = rarity;
        this.refreshAt = refreshAt;
    }

    public int getId() {
        return this.id;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public Material getMaterial() {
        return this.item.getType();
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public long getPrice() {
        return this.price;
    }

    public boolean isCosmetic() {
        return this.cosmetic;
    }

    public String getTheme() {
        return this.theme;
    }

    public String getRarity() {
        return this.rarity;
    }

    public long getRefreshAt() {
        return this.refreshAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= this.refreshAt;
    }
}
