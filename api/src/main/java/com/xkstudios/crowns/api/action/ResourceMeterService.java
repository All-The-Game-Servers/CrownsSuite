package com.xkstudios.crowns.api.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ResourceMeterService {
    private final Map<String, Map<UUID, Integer>> current = new HashMap<>();
    private final Map<String, Integer> maximums = new HashMap<>();

    public void setMaximum(String meterKey, int maximum) {
        this.maximums.put(meterKey, Math.max(1, maximum));
    }

    public int getMaximum(String meterKey) {
        return this.maximums.getOrDefault(meterKey, 100);
    }

    public int get(String meterKey, UUID playerId) {
        return this.current.computeIfAbsent(meterKey, ignored -> new HashMap<>())
                .getOrDefault(playerId, this.getMaximum(meterKey));
    }

    public void set(String meterKey, UUID playerId, int amount) {
        int max = this.getMaximum(meterKey);
        this.current.computeIfAbsent(meterKey, ignored -> new HashMap<>())
                .put(playerId, Math.max(0, Math.min(max, amount)));
    }

    public boolean consume(String meterKey, UUID playerId, int amount) {
        int safeAmount = Math.max(0, amount);
        int value = this.get(meterKey, playerId);
        if (value < safeAmount) {
            return false;
        }
        this.set(meterKey, playerId, value - safeAmount);
        return true;
    }

    public void restore(String meterKey, UUID playerId, int amount) {
        this.set(meterKey, playerId, this.get(meterKey, playerId) + Math.max(0, amount));
    }

    public void clear(UUID playerId) {
        for (Map<UUID, Integer> meter : this.current.values()) {
            meter.remove(playerId);
        }
    }
}
