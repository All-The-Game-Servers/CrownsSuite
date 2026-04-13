package com.xkstudios.crowns.market;

import java.util.UUID;

public class MarketStall {
    private final int id;
    private final UUID ownerUuid;
    private String ownerName;
    private String category;
    private long rentedAt;
    private long expiresAt;
    private long graceEndsAt;
    private boolean active;
    private boolean reminderSent;

    public MarketStall(int id, UUID ownerUuid, String ownerName, String category, long rentedAt, long expiresAt, long graceEndsAt, boolean active, boolean reminderSent) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.category = category;
        this.rentedAt = rentedAt;
        this.expiresAt = expiresAt;
        this.graceEndsAt = graceEndsAt;
        this.active = active;
        this.reminderSent = reminderSent;
    }

    public int getId() {
        return this.id;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public String getOwnerName() {
        return this.ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getCategory() {
        return this.category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public long getRentedAt() {
        return this.rentedAt;
    }

    public void setRentedAt(long rentedAt) {
        this.rentedAt = rentedAt;
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getGraceEndsAt() {
        return this.graceEndsAt;
    }

    public void setGraceEndsAt(long graceEndsAt) {
        this.graceEndsAt = graceEndsAt;
    }

    public boolean isActive() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isReminderSent() {
        return this.reminderSent;
    }

    public void setReminderSent(boolean reminderSent) {
        this.reminderSent = reminderSent;
    }

    public boolean isExpired() {
        return this.active && System.currentTimeMillis() >= this.expiresAt;
    }
}
