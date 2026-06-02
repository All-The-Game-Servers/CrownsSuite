package com.xkstudios.crowns.magic;

public record MagicSpell(
        String key,
        String displayName,
        String description,
        String modelPath,
        int manaCost,
        long cooldownMillis
) {
    public String fullKey() {
        return "magic:" + this.key;
    }
}
