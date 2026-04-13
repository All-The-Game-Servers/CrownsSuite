package com.xkstudios.crowns.market;

import java.util.UUID;

public class StallProfile {
    private final UUID ownerUuid;
    private String ownerName;
    private String stallName;
    private String description;
    private String emblemMaterial;
    private String category;
    private final long purchasedAt;
    private int listingSlots;
    private int spotlightLevel;
    private int prestigeLevel;

    public StallProfile(UUID ownerUuid, String ownerName, String stallName, String description, String emblemMaterial,
                        String category, long purchasedAt, int listingSlots, int spotlightLevel, int prestigeLevel) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.stallName = stallName;
        this.description = description;
        this.emblemMaterial = emblemMaterial;
        this.category = category;
        this.purchasedAt = purchasedAt;
        this.listingSlots = listingSlots;
        this.spotlightLevel = spotlightLevel;
        this.prestigeLevel = prestigeLevel;
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

    public String getStallName() {
        return this.stallName;
    }

    public void setStallName(String stallName) {
        this.stallName = stallName;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmblemMaterial() {
        return this.emblemMaterial;
    }

    public void setEmblemMaterial(String emblemMaterial) {
        this.emblemMaterial = emblemMaterial;
    }

    public String getCategory() {
        return this.category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public long getPurchasedAt() {
        return this.purchasedAt;
    }

    public int getListingSlots() {
        return this.listingSlots;
    }

    public void setListingSlots(int listingSlots) {
        this.listingSlots = listingSlots;
    }

    public int getSpotlightLevel() {
        return this.spotlightLevel;
    }

    public void setSpotlightLevel(int spotlightLevel) {
        this.spotlightLevel = spotlightLevel;
    }

    public int getPrestigeLevel() {
        return this.prestigeLevel;
    }

    public void setPrestigeLevel(int prestigeLevel) {
        this.prestigeLevel = prestigeLevel;
    }
}
