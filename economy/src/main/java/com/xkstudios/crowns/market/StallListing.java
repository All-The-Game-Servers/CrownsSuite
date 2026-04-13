package com.xkstudios.crowns.market;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class StallListing {
    private final int id;
    private final UUID ownerUuid;
    private final ItemStack item;
    private long price;
    private final long createdAt;

    public StallListing(int id, UUID ownerUuid, ItemStack item, long price, long createdAt) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.item = item;
        this.price = price;
        this.createdAt = createdAt;
    }

    public int getId() {
        return this.id;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
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
