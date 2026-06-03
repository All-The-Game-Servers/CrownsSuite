package com.xkstudios.crowns.api.action;

public enum AbilityRank {
    NOVICE(0),
    APPRENTICE(100),
    ADEPT(250),
    EXPERT(450),
    MASTER(700);

    private final int xpRequired;

    AbilityRank(int xpRequired) {
        this.xpRequired = xpRequired;
    }

    public int xpRequired() {
        return this.xpRequired;
    }

    public String displayName() {
        String lower = this.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
