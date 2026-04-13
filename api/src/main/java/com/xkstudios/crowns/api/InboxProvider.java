package com.xkstudios.crowns.api;

import java.util.UUID;

public interface InboxProvider {
    void sendNotification(UUID player, String title, String message);
}
