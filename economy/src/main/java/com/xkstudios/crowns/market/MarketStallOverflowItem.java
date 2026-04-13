package com.xkstudios.crowns.market;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class MarketStallOverflowItem {
    private final long id;
    private final int stallId;
    private final UUID ownerUuid;
    private final String ownerName;
    private final ItemStack item;
    private final long price;
    private final long storedAt;

    public MarketStallOverflowItem(long id, int stallId, UUID ownerUuid, String ownerName, ItemStack item, long price, long storedAt) {
        this.id = id;
        this.stallId = stallId;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.item = item;
        this.price = price;
        this.storedAt = storedAt;
    }

    public long getId() {
        return this.id;
    }

    public int getStallId() {
        return this.stallId;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public String getOwnerName() {
        return this.ownerName;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public long getPrice() {
        return this.price;
    }

    public long getStoredAt() {
        return this.storedAt;
    }
}
