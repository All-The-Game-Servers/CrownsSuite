package com.xkstudios.crowns.inbox;

public record InboxEntry(
        long id,
        String type,
        String title,
        String body,
        long createdAt,
        boolean unread
) {
}
