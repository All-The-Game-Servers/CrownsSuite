package com.xkstudios.crowns.swords;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SwordProfile {
    private final UUID playerId;
    private final Set<String> learnedSkills = new LinkedHashSet<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();

    public SwordProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public Set<String> learnedSkills() {
        return this.learnedSkills;
    }

    public Map<String, String> bindings() {
        return this.bindings;
    }
}
