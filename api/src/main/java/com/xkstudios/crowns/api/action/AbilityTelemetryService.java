package com.xkstudios.crowns.api.action;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AbilityTelemetryService {
    private final Map<UUID, Map<String, EnumMap<AbilityTelemetryCounter, Long>>> counters = new HashMap<>();

    public void increment(UUID playerId, String abilityKey, AbilityTelemetryCounter counter) {
        this.add(playerId, abilityKey, counter, 1L);
    }

    public void add(UUID playerId, String abilityKey, AbilityTelemetryCounter counter, long amount) {
        if (playerId == null || abilityKey == null || abilityKey.isBlank() || counter == null || amount <= 0L) {
            return;
        }
        this.counters
                .computeIfAbsent(playerId, ignored -> new HashMap<>())
                .computeIfAbsent(abilityKey, ignored -> new EnumMap<>(AbilityTelemetryCounter.class))
                .merge(counter, amount, Long::sum);
    }

    public long get(UUID playerId, String abilityKey, AbilityTelemetryCounter counter) {
        return this.counters
                .getOrDefault(playerId, Map.of())
                .getOrDefault(abilityKey, new EnumMap<>(AbilityTelemetryCounter.class))
                .getOrDefault(counter, 0L);
    }

    public Map<AbilityTelemetryCounter, Long> snapshot(UUID playerId, String abilityKey) {
        return Map.copyOf(this.counters
                .getOrDefault(playerId, Map.of())
                .getOrDefault(abilityKey, new EnumMap<>(AbilityTelemetryCounter.class)));
    }

    public long sumByPrefix(UUID playerId, String abilityPrefix, AbilityTelemetryCounter counter) {
        if (playerId == null || abilityPrefix == null || counter == null) {
            return 0L;
        }
        long total = 0L;
        for (Map.Entry<String, EnumMap<AbilityTelemetryCounter, Long>> entry : this.counters.getOrDefault(playerId, Map.of()).entrySet()) {
            if (entry.getKey().startsWith(abilityPrefix)) {
                total += entry.getValue().getOrDefault(counter, 0L);
            }
        }
        return total;
    }

    public void clear(UUID playerId) {
        this.counters.remove(playerId);
    }
}
