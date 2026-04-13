package com.xkstudios.crowns.economy;

import org.bukkit.Material;

public class DemandOrder {
    private final int id;
    private final Material material;
    private final String displayName;
    private final int amount;
    private final long payout;
    private int remainingClaims;
    private final long refreshAt;

    public DemandOrder(int id, Material material, String displayName, int amount, long payout, int remainingClaims, long refreshAt) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.amount = amount;
        this.payout = payout;
        this.remainingClaims = remainingClaims;
        this.refreshAt = refreshAt;
    }

    public int getId() {
        return this.id;
    }

    public Material getMaterial() {
        return this.material;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getAmount() {
        return this.amount;
    }

    public long getPayout() {
        return this.payout;
    }

    public int getRemainingClaims() {
        return this.remainingClaims;
    }

    public void setRemainingClaims(int remainingClaims) {
        this.remainingClaims = remainingClaims;
    }

    public long getRefreshAt() {
        return this.refreshAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= this.refreshAt || this.remainingClaims <= 0;
    }
}
