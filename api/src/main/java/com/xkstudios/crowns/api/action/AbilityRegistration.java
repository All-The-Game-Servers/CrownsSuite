package com.xkstudios.crowns.api.action;

public record AbilityRegistration(
        String pluginKey,
        String abilityKey,
        String displayName,
        String description,
        String modelPath,
        AbilityType type,
        AbilityCategory category,
        int manaCost,
        long cooldownMillis,
        GestureSequence defaultBinding
) {
    public AbilityRegistration(
            String pluginKey,
            String abilityKey,
            String displayName,
            String description,
            String modelPath,
            int manaCost,
            long cooldownMillis,
            GestureSequence defaultBinding
    ) {
        this(pluginKey, abilityKey, displayName, description, modelPath, AbilityType.UTILITY, AbilityCategory.COMBO, manaCost, cooldownMillis, defaultBinding);
    }

    public AbilityRegistration {
        if (pluginKey == null || pluginKey.isBlank()) {
            throw new IllegalArgumentException("pluginKey is required.");
        }
        if (abilityKey == null || abilityKey.isBlank()) {
            throw new IllegalArgumentException("abilityKey is required.");
        }
        displayName = displayName == null || displayName.isBlank() ? abilityKey : displayName;
        description = description == null ? "" : description;
        modelPath = modelPath == null ? "" : modelPath;
        type = type == null ? AbilityType.UTILITY : type;
        category = category == null ? AbilityCategory.COMBO : category;
        manaCost = Math.max(0, manaCost);
        cooldownMillis = Math.max(0L, cooldownMillis);
    }

    public String fullKey() {
        return this.pluginKey + ":" + this.abilityKey;
    }
}
