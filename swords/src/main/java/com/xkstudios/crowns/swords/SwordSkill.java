package com.xkstudios.crowns.swords;

public record SwordSkill(
        String key,
        String displayName,
        String description,
        String modelPath,
        int staminaCost,
        long cooldownMillis
) {
    public String fullKey() {
        return "swords:" + this.key;
    }
}
