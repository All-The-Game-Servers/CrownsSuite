package com.xkstudios.crowns.drugs;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class DrugBusiness {
    private final UUID ownerId;
    private long dirtyCash;
    private int labTier;
    private int storageTier;
    private int processorTier;
    private int seeds;
    private int supplies;
    private final Map<DrugProduct, Integer> rawStock = new EnumMap<>(DrugProduct.class);
    private final Map<DrugProduct, Integer> packagedStock = new EnumMap<>(DrugProduct.class);

    public DrugBusiness(UUID ownerId) {
        this.ownerId = ownerId;
        this.labTier = 1;
        this.storageTier = 1;
        this.processorTier = 1;
        this.seeds = 12;
        this.supplies = 8;
        for (DrugProduct product : DrugProduct.values()) {
            this.rawStock.put(product, 0);
            this.packagedStock.put(product, 0);
        }
    }

    public UUID ownerId() {
        return ownerId;
    }

    public long dirtyCash() {
        return dirtyCash;
    }

    public void setDirtyCash(long dirtyCash) {
        this.dirtyCash = dirtyCash;
    }

    public int labTier() {
        return labTier;
    }

    public void setLabTier(int labTier) {
        this.labTier = labTier;
    }

    public int storageTier() {
        return storageTier;
    }

    public void setStorageTier(int storageTier) {
        this.storageTier = storageTier;
    }

    public int processorTier() {
        return processorTier;
    }

    public void setProcessorTier(int processorTier) {
        this.processorTier = processorTier;
    }

    public int seeds() {
        return seeds;
    }

    public void setSeeds(int seeds) {
        this.seeds = seeds;
    }

    public int supplies() {
        return supplies;
    }

    public void setSupplies(int supplies) {
        this.supplies = supplies;
    }

    public int raw(DrugProduct product) {
        return this.rawStock.getOrDefault(product, 0);
    }

    public void setRaw(DrugProduct product, int amount) {
        this.rawStock.put(product, Math.max(0, amount));
    }

    public int packaged(DrugProduct product) {
        return this.packagedStock.getOrDefault(product, 0);
    }

    public void setPackaged(DrugProduct product, int amount) {
        this.packagedStock.put(product, Math.max(0, amount));
    }

    public int storageCap() {
        return 24 + (this.storageTier - 1) * 12;
    }
}
