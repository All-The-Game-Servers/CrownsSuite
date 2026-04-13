package com.xkstudios.crowns.api;

import java.util.UUID;

public interface EconomyLedgerProvider {
    void recordSource(UUID playerUuid, String playerName, String category, long amount, String detail);

    void recordSink(UUID playerUuid, String playerName, String category, long amount, String detail);

    void recordServerSink(String category, long amount, String detail);
}
