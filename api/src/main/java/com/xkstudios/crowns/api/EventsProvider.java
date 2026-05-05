package com.xkstudios.crowns.api;

import org.bukkit.World;

public interface EventsProvider {
    String getDimensionLockMessage(World.Environment environment);

    default String getActiveEventLabel() {
        return "No active event";
    }

    default String getStatusLabel() {
        return "Offline";
    }

    default String getPlayerProgressSummary(java.util.UUID playerId, String playerName) {
        return "No event progress yet.";
    }

    default java.util.List<String> getLiveEventSummaries() {
        return java.util.List.of();
    }

    default java.util.List<String> getRecentSuiteActivitySummaries() {
        return java.util.List.of();
    }
}
