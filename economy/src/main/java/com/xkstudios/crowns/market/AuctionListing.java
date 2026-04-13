/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.inventory.ItemStack
 */
package com.xkstudios.crowns.market;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class AuctionListing {
    private final String id;
    private final UUID sellerUuid;
    private final ItemStack item;
    private long startingBid;
    private final long listedAt;
    private final long expiresAt;
    private boolean ended;
    private UUID highBidder;
    private long highBid;

    public AuctionListing(String id, UUID sellerUuid, ItemStack item, long startingBid, long listedAt, long expiresAt, UUID highBidder, long highBid, boolean ended) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.item = item;
        this.startingBid = startingBid;
        this.listedAt = listedAt;
        this.expiresAt = expiresAt;
        this.highBidder = highBidder;
        this.highBid = highBid;
        this.ended = ended;
    }

    public String getId() {
        return this.id;
    }

    public UUID getSellerUuid() {
        return this.sellerUuid;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public long getStartingBid() {
        return this.startingBid;
    }

    public void setStartingBid(long b) {
        this.startingBid = b;
    }

    public long getListedAt() {
        return this.listedAt;
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    public boolean isEnded() {
        return this.ended;
    }

    public void setEnded(boolean e) {
        this.ended = e;
    }

    public UUID getHighBidder() {
        return this.highBidder;
    }

    public void setHighBidder(UUID u) {
        this.highBidder = u;
    }

    public long getHighBid() {
        return this.highBid;
    }

    public void setHighBid(long b) {
        this.highBid = b;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAt;
    }

    public boolean hasBids() {
        return this.highBidder != null;
    }

    public long getMinNextBid() {
        return this.hasBids() ? this.highBid + Math.max(1L, this.highBid / 20L) : this.startingBid;
    }
}

