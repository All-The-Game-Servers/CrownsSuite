package com.xkstudios.crowns.api;

import org.bukkit.World;

public interface EventsProvider {
    String getDimensionLockMessage(World.Environment environment);
}
