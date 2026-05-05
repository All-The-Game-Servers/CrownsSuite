package com.xkstudios.crowns.api;

import java.util.UUID;

public record SuiteActivity(
        String source,
        String type,
        String title,
        String detail,
        UUID actor,
        long createdAt
) {
}
