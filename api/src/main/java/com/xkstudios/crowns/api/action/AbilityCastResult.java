package com.xkstudios.crowns.api.action;

public record AbilityCastResult(boolean success, String message) {
    public static AbilityCastResult ok() {
        return new AbilityCastResult(true, "");
    }

    public static AbilityCastResult ok(String message) {
        return new AbilityCastResult(true, message == null ? "" : message);
    }

    public static AbilityCastResult fail(String message) {
        return new AbilityCastResult(false, message == null ? "" : message);
    }
}
