package com.xkstudios.crowns.moderation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class StaffRole {
    private final String key;
    private final String displayName;
    private final String color;
    private final Set<StaffCapability> capabilities;

    public StaffRole(String key, String displayName, String color, Set<StaffCapability> capabilities) {
        this.key = key;
        this.displayName = displayName;
        this.color = color;
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public String key() {
        return this.key;
    }

    public String displayName() {
        return this.displayName;
    }

    public String color() {
        return this.color;
    }

    public Set<StaffCapability> capabilities() {
        return this.capabilities;
    }

    public boolean has(StaffCapability capability) {
        return this.capabilities.contains(capability);
    }
}
