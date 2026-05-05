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
    private static final Map<String, ModuleDescriptor> expectedModules = new LinkedHashMap<>();
    private static final Map<String, ModuleDescriptor> modules = new LinkedHashMap<>();
    private static final Map<String, ModuleHealthProvider> moduleHealthProviders = new LinkedHashMap<>();
    private static final List<SuiteAlert> alerts = new CopyOnWriteArrayList<>();
    private static final List<SuiteAlertListener> alertListeners = new CopyOnWriteArrayList<>();
    private static final List<SuiteActivity> activities = new CopyOnWriteArrayList<>();
    private static final List<SuiteActivityListener> activityListeners = new CopyOnWriteArrayList<>();

    static {
        registerExpectedModule(new ModuleDescriptor("api", "CrownsAPI", "CrownsAPI", "unknown", "1.4.0", List.of(), List.of(), List.of("data", "gui", "modules", "resource-pack")));
        registerExpectedModule(new ModuleDescriptor("economy", "CrownsEconomy", "CrownsEconomy", "unknown", "1.4.0", List.of("CrownsAPI"), List.of(), List.of("economy", "market-activity")));
        registerExpectedModule(new ModuleDescriptor("admin", "CrownsAdmin", "CrownsAdmin", "unknown", "1.4.0", List.of("CrownsAPI"), List.of("CrownsEconomy"), List.of("moderation", "analytics")));
        registerExpectedModule(new ModuleDescriptor("events", "CrownsEvents", "CrownsEvents", "unknown", "1.4.0", List.of("CrownsAPI"), List.of("CrownsEconomy"), List.of("events", "activity-hooks")));
        registerExpectedModule(new ModuleDescriptor("drugs", "CrownsDrugs", "CrownsDrugs", "unknown", "1.4.0", List.of("CrownsAPI"), List.of("CrownsEconomy"), List.of("drugs")));
        registerExpectedModule(new ModuleDescriptor("mmo", "CrownsMMO", "CrownsMMO", "unknown", "1.4.0", List.of("CrownsAPI"), List.of("CrownsTerrain", "CrownsEconomy", "CrownsEvents", "CrownsAdmin", "CrownsDrugs"), List.of("mmo")));
        registerExpectedModule(new ModuleDescriptor("terrain", "CrownsTerrain", "CrownsTerrain", "unknown", "1.4.0", List.of("CrownsAPI"), List.of("CrownsEvents"), List.of("terrain")));
    }

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

    public static void registerExpectedModule(ModuleDescriptor descriptor) {
        if (descriptor != null) {
            expectedModules.put(descriptor.key(), descriptor);
        }
    }

    public static void registerModule(ModuleDescriptor descriptor, ModuleHealthProvider healthProvider) {
        if (descriptor != null) {
            modules.put(descriptor.key(), descriptor);
            moduleHealthProviders.put(descriptor.key(), healthProvider == null
                    ? () -> ModuleHealth.ready(descriptor, "Loaded.")
                    : healthProvider);
        }
    }

    public static void unregisterModule(String key) {
        modules.remove(key);
        moduleHealthProviders.remove(key);
    }

    public static Collection<ModuleDescriptor> getModuleDescriptors() {
        return List.copyOf(modules.values());
    }

    public static List<ModuleHealth> getModuleHealth() {
        List<ModuleHealth> result = new ArrayList<>();
        for (Map.Entry<String, ModuleDescriptor> entry : expectedModules.entrySet()) {
            result.add(getModuleHealth(entry.getKey()));
        }
        for (String key : modules.keySet()) {
            if (!expectedModules.containsKey(key)) {
                result.add(getModuleHealth(key));
            }
        }
        return result;
    }

    public static ModuleHealth getModuleHealth(String key) {
        ModuleDescriptor descriptor = modules.getOrDefault(key, expectedModules.get(key));
        if (descriptor == null) {
            descriptor = new ModuleDescriptor(key, key, key, "unknown", "unknown", List.of(), List.of(), List.of());
        }
        ModuleHealthProvider provider = moduleHealthProviders.get(key);
        if (provider == null) {
            return ModuleHealth.of(descriptor, ServiceState.MISSING, "Plugin is not installed or has not registered with CrownsAPI.", List.of("Install or enable " + descriptor.pluginName() + "."));
        }
        try {
            ModuleHealth health = provider.getModuleHealth();
            return health == null ? ModuleHealth.of(descriptor, ServiceState.FAILED, "Health provider returned no status.", List.of("Check " + descriptor.pluginName() + " startup logs.")) : health;
        } catch (RuntimeException exception) {
            return ModuleHealth.of(descriptor, ServiceState.FAILED, "Health check failed: " + exception.getMessage(), List.of("Check " + descriptor.pluginName() + " console errors."));
        }
    }

    public static void registerAlertListener(SuiteAlertListener listener) {
        if (listener != null && !alertListeners.contains(listener)) {
            alertListeners.add(listener);
        }
    }

    public static void unregisterAlertListener(SuiteAlertListener listener) {
        alertListeners.remove(listener);
    }

    public static void registerActivityListener(SuiteActivityListener listener) {
        if (listener != null && !activityListeners.contains(listener)) {
            activityListeners.add(listener);
        }
    }

    public static void unregisterActivityListener(SuiteActivityListener listener) {
        activityListeners.remove(listener);
    }

    public static List<SuiteActivity> getRecentActivities(int limit) {
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0 || activities.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, activities.size() - safeLimit);
        return new ArrayList<>(activities.subList(start, activities.size()));
    }

    public static void publishActivity(String source, String type, String title, String detail, UUID actor) {
        SuiteActivity activity = new SuiteActivity(source, type, title, detail, actor, System.currentTimeMillis());
        activities.add(activity);
        while (activities.size() > 100) {
            activities.remove(0);
        }
        for (SuiteActivityListener listener : activityListeners) {
            listener.onSuiteActivity(activity);
        }
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
