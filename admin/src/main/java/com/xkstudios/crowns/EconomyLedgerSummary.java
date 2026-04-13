package com.xkstudios.crowns.analytics;

import java.util.Map;

public record EconomyLedgerSummary(
        Map<String, Long> sources,
        Map<String, Long> sinks,
        long totalSources,
        long totalSinks
) {
    public long netCreated() {
        return this.totalSources - this.totalSinks;
    }
}
