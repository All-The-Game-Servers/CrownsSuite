package com.xkstudios.crowns.analytics;

import java.util.UUID;

public record PlaytimeEntry(UUID uuid, String name, long seconds) {
}
