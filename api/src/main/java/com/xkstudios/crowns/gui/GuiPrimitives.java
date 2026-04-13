package com.xkstudios.crowns.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuiPrimitives {
    private GuiPrimitives() {
    }

    public static int clampPage(int requestedPage, int totalItems, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int maxPage = Math.max(0, (int) Math.ceil(totalItems / (double) safePageSize) - 1);
        return Math.max(0, Math.min(requestedPage, maxPage));
    }

    public static <T> List<T> page(List<T> source, int page, int pageSize) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int safePageSize = Math.max(1, pageSize);
        int safePage = clampPage(page, source.size(), safePageSize);
        int start = safePage * safePageSize;
        int end = Math.min(source.size(), start + safePageSize);
        return new ArrayList<>(source.subList(start, end));
    }

    public static boolean matchesQuery(String value, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
