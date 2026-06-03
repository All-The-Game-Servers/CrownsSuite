package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.action.AbilityRank;

public record MagicSpell(
        String key,
        String displayName,
        String description,
        String modelPath,
        String schoolKey,
        String schoolName,
        AbilityRank rank,
        int manaCost,
        long cooldownMillis
) {
    public String fullKey() {
        return "magic:" + this.key;
    }
}
