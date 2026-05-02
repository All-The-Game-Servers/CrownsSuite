package com.xkstudios.crowns.mmo.floor;

import org.bukkit.World;
import org.bukkit.entity.EntityType;

public record MmoFloor(
        int number,
        String worldName,
        World.Environment environment,
        int borderSize,
        int requiredFloor,
        double difficulty,
        EntityType bossType,
        String bossName,
        double bossHealth,
        double bossDamage,
        double bossArenaRadius,
        long resourceTier
) {
    public boolean isFirstFloor() {
        return this.number == 1;
    }

    public int nextFloor() {
        return this.number + 1;
    }
}
