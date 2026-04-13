package com.xkstudios.crowns.playerstate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AfkManager {
    private final Set<UUID> manualAfk = ConcurrentHashMap.newKeySet();

    public boolean toggle(UUID uuid) {
        if (this.manualAfk.contains(uuid)) {
            this.manualAfk.remove(uuid);
            return false;
        }
        this.manualAfk.add(uuid);
        return true;
    }

    public void clear(UUID uuid) {
        this.manualAfk.remove(uuid);
    }

    public boolean isManualAfk(UUID uuid) {
        return this.manualAfk.contains(uuid);
    }
}
