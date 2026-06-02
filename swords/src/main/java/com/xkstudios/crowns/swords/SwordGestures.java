package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.action.GestureSequence;
import com.xkstudios.crowns.api.action.InputGesture;
import java.util.Arrays;
import java.util.Locale;

public final class SwordGestures {
    private SwordGestures() {
    }

    public static GestureSequence fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            String[] parts = key.toUpperCase(Locale.ROOT).split(">");
            InputGesture[] gestures = Arrays.stream(parts)
                    .map(String::trim)
                    .map(InputGesture::valueOf)
                    .toArray(InputGesture[]::new);
            return GestureSequence.of(850L, gestures);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String normalize(String input) {
        return input == null ? "" : input.trim().toUpperCase(Locale.ROOT).replace("+", "_").replace(" ", "_");
    }
}
