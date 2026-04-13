package com.xkstudios.crowns.analytics;

public record EconomyLedgerEntry(
        long recordedAt,
        String category,
        String direction,
        long amount,
        String playerName,
        String detail
) {
}
