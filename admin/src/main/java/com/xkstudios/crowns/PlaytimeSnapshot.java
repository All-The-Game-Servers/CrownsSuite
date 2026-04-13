package com.xkstudios.crowns.analytics;

import java.util.UUID;

public record PlaytimeSnapshot(
        UUID uuid,
        String name,
        long lifetimeSeconds,
        long currentSessionSeconds,
        long todaySeconds,
        long last7DaysSeconds,
        long last30DaysSeconds,
        long firstJoinAt,
        long lastJoinAt,
        long lastQuitAt,
        boolean online
) {
}
