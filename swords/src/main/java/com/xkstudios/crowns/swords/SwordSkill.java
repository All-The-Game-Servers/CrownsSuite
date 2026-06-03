package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.action.AbilityRank;

public record SwordSkill(
        String key,
        String displayName,
        String description,
        String modelPath,
        String styleKey,
        String styleName,
        AbilityRank rank,
        int staminaCost,
        long cooldownMillis
) {
    public String fullKey() {
        return "swords:" + this.key;
    }
}
