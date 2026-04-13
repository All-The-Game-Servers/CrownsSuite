package com.xkstudios.crowns.analytics;

public enum PlaytimePeriod {
    TODAY("today", "Today", 1),
    DAYS_7("7d", "Last 7 Days", 7),
    DAYS_30("30d", "Last 30 Days", 30),
    ALL("all", "All Time", 0);

    private final String key;
    private final String label;
    private final int dayWindow;

    PlaytimePeriod(String key, String label, int dayWindow) {
        this.key = key;
        this.label = label;
        this.dayWindow = dayWindow;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return this.label;
    }

    public int dayWindow() {
        return this.dayWindow;
    }

    public static PlaytimePeriod fromKey(String key) {
        for (PlaytimePeriod value : values()) {
            if (value.key.equalsIgnoreCase(key)) {
                return value;
            }
        }
        return ALL;
    }
}
