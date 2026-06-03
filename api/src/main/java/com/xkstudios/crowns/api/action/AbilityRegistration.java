package com.xkstudios.crowns.api.action;

public record AbilityRegistration(
        String pluginKey,
        String abilityKey,
        String displayName,
        String description,
        String modelPath,
        AbilityType type,
        AbilityCategory category,
        String familyKey,
        String familyName,
        AbilityRank rank,
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
        this(pluginKey, abilityKey, displayName, description, modelPath, AbilityType.UTILITY, AbilityCategory.COMBO, "", "", AbilityRank.NOVICE, manaCost, cooldownMillis, defaultBinding);
    }

    public AbilityRegistration(
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
        this(pluginKey, abilityKey, displayName, description, modelPath, type, category, "", "", AbilityRank.NOVICE, manaCost, cooldownMillis, defaultBinding);
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
        familyKey = familyKey == null ? "" : familyKey;
        familyName = familyName == null || familyName.isBlank() ? familyKey : familyName;
        rank = rank == null ? AbilityRank.NOVICE : rank;
        manaCost = Math.max(0, manaCost);
        cooldownMillis = Math.max(0L, cooldownMillis);
    }

    public String fullKey() {
        return this.pluginKey + ":" + this.abilityKey;
    }
}
