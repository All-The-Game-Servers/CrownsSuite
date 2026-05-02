package com.xkstudios.crowns.api;

import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.gui.SuiteGuiManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;

public final class CrownsAPI {
    private static EconomyProvider economyProvider;
    private static PlayerDataProvider playerDataProvider;
    private static InboxProvider inboxProvider;
    private static EconomyLedgerProvider ledgerProvider;
    private static EventsProvider eventsProvider;
    private static MmoProvider mmoProvider;
    private static TerrainProvider terrainProvider;
    private static DataManager dataManager;
    private static SuiteGuiManager suiteGuiManager;
    private static ResourcePackService resourcePackService;
    private static final Map<String, SuiteSection> sections = new LinkedHashMap<>();
    private static final List<SuiteAlert> alerts = new CopyOnWriteArrayList<>();
    private static final List<SuiteAlertListener> alertListeners = new CopyOnWriteArrayList<>();

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

    public static void setMmoProvider(MmoProvider provider) {
        mmoProvider = provider;
    }

    public static MmoProvider getMmo() {
        return mmoProvider;
    }

    public static void setTerrainProvider(TerrainProvider provider) {
        terrainProvider = provider;
    }

    public static TerrainProvider getTerrain() {
        return terrainProvider;
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

    public static void setResourcePackService(ResourcePackService service) {
        resourcePackService = service;
    }

    public static ResourcePackService getResourcePackService() {
        return resourcePackService;
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

    public static void registerAlertListener(SuiteAlertListener listener) {
        if (listener != null && !alertListeners.contains(listener)) {
            alertListeners.add(listener);
        }
    }

    public static void unregisterAlertListener(SuiteAlertListener listener) {
        alertListeners.remove(listener);
    }

    public static List<SuiteAlert> getRecentAlerts(int limit) {
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0 || alerts.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, alerts.size() - safeLimit);
        return new ArrayList<>(alerts.subList(start, alerts.size()));
    }

    public static void publishAlert(String source, String title, String body, UUID targetPlayer, boolean inboxBacked) {
        SuiteAlert alert = new SuiteAlert(source, title, body, targetPlayer, System.currentTimeMillis());
        alerts.add(alert);
        while (alerts.size() > 50) {
            alerts.remove(0);
        }
        for (SuiteAlertListener listener : alertListeners) {
            listener.onAlert(alert);
        }
        if (inboxBacked && inboxProvider != null && targetPlayer != null) {
            inboxProvider.sendNotification(targetPlayer, title, body == null ? "" : body);
        }
    }

    public static void openSuiteHome(Player player) {
        if (player != null && suiteGuiManager != null) {
            suiteGuiManager.openHome(player);
        }
    }
}
