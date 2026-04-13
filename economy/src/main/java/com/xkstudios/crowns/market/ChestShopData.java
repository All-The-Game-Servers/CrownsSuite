/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Location
 *  org.bukkit.World
 */
package com.xkstudios.crowns.market;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class ChestShopData {
    private final String id;
    private final UUID owner;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String itemData;
    private final long price;

    public ChestShopData(String id, UUID owner, String world, int x, int y, int z, String itemData, long price) {
        this.id = id;
        this.owner = owner;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.itemData = itemData;
        this.price = price;
    }

    public String getId() {
        return this.id;
    }

    public UUID getOwner() {
        return this.owner;
    }

    public String getWorld() {
        return this.world;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public String getItemData() {
        return this.itemData;
    }

    public long getPrice() {
        return this.price;
    }

    public String key() {
        return this.world + ":" + this.x + ":" + this.y + ":" + this.z;
    }

    public Location location() {
        World w = Bukkit.getWorld((String)this.world);
        return w != null ? new Location(w, (double)this.x, (double)this.y, (double)this.z) : null;
    }
}

