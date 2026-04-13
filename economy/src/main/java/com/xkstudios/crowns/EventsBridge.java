package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EventsProvider;
import org.bukkit.World;

public class EventsBridge {
    public String getDimensionLockMessage(World.Environment environment) {
        EventsProvider provider = CrownsAPI.getEvents();
        return provider == null ? null : provider.getDimensionLockMessage(environment);
    }
}
