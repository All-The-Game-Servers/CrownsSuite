package com.xkstudios.crowns.api.action;

import java.util.List;

public record GestureSequence(List<InputGesture> gestures, long maxIntervalMillis) {
    public GestureSequence {
        gestures = gestures == null ? List.of() : List.copyOf(gestures);
        maxIntervalMillis = Math.max(50L, maxIntervalMillis);
        if (gestures.isEmpty()) {
            throw new IllegalArgumentException("Gesture sequence cannot be empty.");
        }
    }

    public static GestureSequence single(InputGesture gesture) {
        return new GestureSequence(List.of(gesture), 750L);
    }

    public static GestureSequence of(long maxIntervalMillis, InputGesture... gestures) {
        return new GestureSequence(List.of(gestures), maxIntervalMillis);
    }

    public String displayName() {
        return String.join(" -> ", this.gestures.stream().map(InputGesture::displayName).toList());
    }
}
