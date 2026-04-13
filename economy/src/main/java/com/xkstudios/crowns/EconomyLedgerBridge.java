package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyLedgerProvider;
import java.util.UUID;

public class EconomyLedgerBridge {
    public void recordSource(UUID playerUuid, String playerName, String category, long amount, String detail) {
        EconomyLedgerProvider provider = CrownsAPI.getEconomyLedger();
        if (provider != null) {
            provider.recordSource(playerUuid, playerName, category, amount, detail);
        }
    }

    public void recordSink(UUID playerUuid, String playerName, String category, long amount, String detail) {
        EconomyLedgerProvider provider = CrownsAPI.getEconomyLedger();
        if (provider != null) {
            provider.recordSink(playerUuid, playerName, category, amount, detail);
        }
    }

    public void recordServerSink(String category, long amount, String detail) {
        EconomyLedgerProvider provider = CrownsAPI.getEconomyLedger();
        if (provider != null) {
            provider.recordServerSink(category, amount, detail);
        }
    }
}
