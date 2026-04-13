package com.xkstudios.crowns.market;

import org.bukkit.inventory.ItemStack;

public class MarketStallListing {
    private final int id;
    private final int stallId;
    private final ItemStack item;
    private long price;
    private final long createdAt;

    public MarketStallListing(int id, int stallId, ItemStack item, long price, long createdAt) {
        this.id = id;
        this.stallId = stallId;
        this.item = item;
        this.price = price;
        this.createdAt = createdAt;
    }

    public int getId() {
        return this.id;
    }

    public int getStallId() {
        return this.stallId;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public long getPrice() {
        return this.price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public long getCreatedAt() {
        return this.createdAt;
    }
}
