package com.xkstudios.crowns.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record TerrainPoint(
        int floor,
        String worldName,
        String type,
        String key,
        String displayName,
        int x,
        int y,
        int z
) {
    public Location toLocation() {
        World world = Bukkit.getWorld(this.worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, this.x + 0.5D, this.y, this.z + 0.5D);
    }

    public String coordinateSummary() {
        return "X " + this.x + ", Y " + this.y + ", Z " + this.z;
    }
}
