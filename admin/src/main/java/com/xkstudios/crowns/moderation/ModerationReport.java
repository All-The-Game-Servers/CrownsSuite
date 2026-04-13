package com.xkstudios.crowns.moderation;

import java.util.UUID;

public record ModerationReport(
        long id,
        UUID reporterUuid,
        String reporterName,
        UUID targetUuid,
        String targetName,
        String reason,
        String status,
        String claimedByName,
        long createdAt
) {
}
