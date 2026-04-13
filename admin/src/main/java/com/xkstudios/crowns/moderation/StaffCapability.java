package com.xkstudios.crowns.moderation;

public enum StaffCapability {
    INSPECT("inspect"),
    INVENTORY_EDIT("inventory.edit"),
    REPORTS("reports"),
    WARN("warn"),
    NOTE("note"),
    KICK("kick"),
    MUTE("mute"),
    FREEZE("freeze"),
    BAN("ban"),
    ROLLBACK("rollback"),
    STAFFMODE("staffmode"),
    VANISH("vanish"),
    PUPPET("puppet"),
    ROLES("roles");

    private final String key;

    StaffCapability(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public String permission() {
        return "crowns.mod." + this.key;
    }

    public static StaffCapability fromKey(String key) {
        for (StaffCapability capability : values()) {
            if (capability.key.equalsIgnoreCase(key)) {
                return capability;
            }
        }
        return null;
    }
}
