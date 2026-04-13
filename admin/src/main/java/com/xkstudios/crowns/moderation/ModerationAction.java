package com.xkstudios.crowns.moderation;

public record ModerationAction(
        long id,
        String actorName,
        String targetName,
        String actionType,
        String reason,
        long createdAt,
        long expiresAt,
        boolean active
) {
}
