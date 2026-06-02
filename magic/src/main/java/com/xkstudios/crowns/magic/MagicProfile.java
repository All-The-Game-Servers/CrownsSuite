package com.xkstudios.crowns.magic;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MagicProfile {
    private final UUID playerId;
    private final Set<String> learnedSpells = new LinkedHashSet<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();

    public MagicProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public Set<String> learnedSpells() {
        return this.learnedSpells;
    }

    public Map<String, String> bindings() {
        return this.bindings;
    }
}
