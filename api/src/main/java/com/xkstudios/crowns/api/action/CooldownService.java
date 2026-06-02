package com.xkstudios.crowns.api.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CooldownService {
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public boolean isReady(UUID playerId, String key) {
        return this.remainingMillis(playerId, key) <= 0L;
    }

    public long remainingMillis(UUID playerId, String key) {
        Map<String, Long> playerCooldowns = this.cooldowns.get(playerId);
        if (playerCooldowns == null) {
            return 0L;
        }
        long expiresAt = playerCooldowns.getOrDefault(key, 0L);
        return Math.max(0L, expiresAt - System.currentTimeMillis());
    }

    public void start(UUID playerId, String key, long durationMillis) {
        if (durationMillis <= 0L) {
            return;
        }
        this.cooldowns.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(key, System.currentTimeMillis() + durationMillis);
    }

    public void clear(UUID playerId) {
        this.cooldowns.remove(playerId);
    }
}
