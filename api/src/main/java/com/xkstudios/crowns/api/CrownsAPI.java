package com.xkstudios.crowns.api;

import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.gui.SuiteGuiManager;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

public final class CrownsAPI {
    private static EconomyProvider economyProvider;
    private static PlayerDataProvider playerDataProvider;
    private static InboxProvider inboxProvider;
    private static EconomyLedgerProvider ledgerProvider;
    private static EventsProvider eventsProvider;
    private static DataManager dataManager;
    private static SuiteGuiManager suiteGuiManager;
    private static final Map<String, SuiteSection> sections = new LinkedHashMap<>();

    private CrownsAPI() {
    }

    public static void setEconomyProvider(EconomyProvider provider) {
        economyProvider = provider;
    }

    public static EconomyProvider getEconomy() {
        return economyProvider;
    }

    public static void setPlayerDataProvider(PlayerDataProvider provider) {
        playerDataProvider = provider;
    }

    public static PlayerDataProvider getPlayerData() {
        return playerDataProvider;
    }

    public static void setInboxProvider(InboxProvider provider) {
        inboxProvider = provider;
    }

    public static InboxProvider getInbox() {
        return inboxProvider;
    }

    public static void setEconomyLedgerProvider(EconomyLedgerProvider provider) {
        ledgerProvider = provider;
    }

    public static EconomyLedgerProvider getEconomyLedger() {
        return ledgerProvider;
    }

    public static void setEventsProvider(EventsProvider provider) {
        eventsProvider = provider;
    }

    public static EventsProvider getEvents() {
        return eventsProvider;
    }

    public static void setDataManager(DataManager manager) {
        dataManager = manager;
    }

    public static DataManager getDataManager() {
        return dataManager;
    }

    public static void setSuiteGui(SuiteGuiManager suiteGui) {
        suiteGuiManager = suiteGui;
    }

    public static SuiteGuiManager getSuiteGui() {
        return suiteGuiManager;
    }

    public static void registerSection(SuiteSection section) {
        if (section != null) {
            sections.put(section.key(), section);
        }
    }

    public static void unregisterSection(String key) {
        sections.remove(key);
    }

    public static SuiteSection getSection(String key) {
        return sections.get(key);
    }

    public static Collection<SuiteSection> getSections() {
        return List.copyOf(sections.values());
    }

    public static void clearSections() {
        sections.clear();
    }

    public static void openSuiteHome(Player player) {
        if (player != null && suiteGuiManager != null) {
            suiteGuiManager.openHome(player);
        }
    }
}
