package com.xkstudios.crowns.api;

import java.util.UUID;

public record SuiteAlert(
        String source,
        String title,
        String body,
        UUID targetPlayer,
        long createdAt
) {
}
