package com.xkstudios.crowns.api;

import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.data.PlayerData;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.gui.SuiteGuiManager;
import com.xkstudios.crowns.gui.SuiteMenuListener;
import com.xkstudios.crowns.inbox.InboxManager;
import com.xkstudios.crowns.api.action.CooldownService;
import com.xkstudios.crowns.api.action.DefaultActionInputService;
import com.xkstudios.crowns.api.action.ParticlePatternService;
import com.xkstudios.crowns.api.action.ResourceMeterService;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsAPIPlugin extends JavaPlugin {
    private DataManager dataManager;
    private InboxManager inboxManager;
    private SuiteGuiManager suiteGuiManager;
    private ResourcePackService resourcePackService;
    private DefaultActionInputService actionInputService;
    private CooldownService cooldownService;
    private ResourceMeterService resourceMeterService;
    private ParticlePatternService particlePatternService;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Currency.reload(this);
        this.dataManager = new DataManager(this);
        this.dataManager.setStartingBalance(this.getConfig().getLong("player-data.starting-balance", 500L));
        this.dataManager.initialize();
        this.inboxManager = new InboxManager(this, this.dataManager);
        this.resourcePackService = new ResourcePackService(this);
        this.suiteGuiManager = new SuiteGuiManager(this);
        this.actionInputService = new DefaultActionInputService();
        this.cooldownService = new CooldownService();
        this.resourceMeterService = new ResourceMeterService();
        this.particlePatternService = new ParticlePatternService(this);
        CrownsAPI.setDataManager(this.dataManager);
        CrownsAPI.setSuiteGui(this.suiteGuiManager);
        CrownsAPI.setResourcePackService(this.resourcePackService);
        CrownsAPI.setActionInputService(this.actionInputService);
        CrownsAPI.setCooldownService(this.cooldownService);
        CrownsAPI.setResourceMeterService(this.resourceMeterService);
        CrownsAPI.setParticlePatternService(this.particlePatternService);
        CrownsAPI.registerModule(new ModuleDescriptor(
                "api",
                "CrownsAPI",
                "CrownsAPI",
                this.getDescription().getVersion(),
                "0.1.2",
                List.of(),
                List.of(),
                List.of("data", "gui", "modules", "resource-pack", "inbox", "input", "particles", "resources", "action-combat")
        ), this::apiHealth);
        CrownsAPI.setPlayerDataProvider(new PlayerDataProvider() {
            @Override
            public PlayerData getOrCreate(UUID uuid, String name) {
                return dataManager.getOrCreate(uuid, name);
            }

            @Override
            public void save(PlayerData data) {
                dataManager.savePlayer(data);
            }

            @Override
            public List<PlayerData> getTopBalances(int limit) {
                return dataManager.getTopBalances(limit);
            }
        });
        CrownsAPI.setInboxProvider(this.inboxManager);
        Bukkit.getPluginManager().registerEvents(new SuiteMenuListener(), this);
        Bukkit.getPluginManager().registerEvents(this.resourcePackService, this);
        Bukkit.getPluginManager().registerEvents(this.actionInputService, this);
        CapiCommand command = new CapiCommand(this);
        if (this.getCommand("capi") != null) {
            this.getCommand("capi").setExecutor(command);
            this.getCommand("capi").setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        CrownsAPI.setEconomyProvider(null);
        CrownsAPI.setEconomyLedgerProvider(null);
        CrownsAPI.setEventsProvider(null);
        CrownsAPI.setMmoProvider(null);
        CrownsAPI.setFloorRuntimeProvider(null);
        CrownsAPI.setInboxProvider(null);
        CrownsAPI.setPlayerDataProvider(null);
        CrownsAPI.setDataManager(null);
        CrownsAPI.setSuiteGui(null);
        CrownsAPI.setResourcePackService(null);
        CrownsAPI.setActionInputService(null);
        CrownsAPI.setCooldownService(null);
        CrownsAPI.setResourceMeterService(null);
        CrownsAPI.setParticlePatternService(null);
        CrownsAPI.unregisterModule("api");
        CrownsAPI.clearSections();
        if (this.dataManager != null) {
            this.dataManager.close();
        }
    }

    private ModuleHealth apiHealth() {
        List<String> warnings = new java.util.ArrayList<>();
        boolean databaseOnline = false;
        try {
            databaseOnline = this.dataManager != null
                    && this.dataManager.getConnection() != null
                    && !this.dataManager.getConnection().isClosed();
        } catch (SQLException exception) {
            warnings.add("Database check failed: " + exception.getMessage());
        }
        if (!databaseOnline) {
            warnings.add("Shared crowns.db connection is offline.");
        }
        if (this.suiteGuiManager == null) {
            warnings.add("Suite GUI manager is not registered.");
        }
        if (this.resourcePackService == null) {
            warnings.add("Resource-pack service is not registered.");
        } else if (!this.resourcePackService.isEnabled()) {
            warnings.add("Resource-pack metadata is disabled in API config.");
        }
        if (this.actionInputService == null) {
            warnings.add("Action input service is not registered.");
        }
        if (this.resourceMeterService == null || this.cooldownService == null) {
            warnings.add("Ability resource/cooldown services are not registered.");
        }
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "api",
                "CrownsAPI",
                "CrownsAPI",
                this.getDescription().getVersion(),
                "0.1.2",
                List.of(),
                List.of(),
                List.of("data", "gui", "modules", "resource-pack", "inbox", "input", "particles", "resources", "action-combat")
        );
        if (!databaseOnline) {
            return ModuleHealth.of(descriptor, ServiceState.FAILED, "Core API services are unavailable.", warnings);
        }
        return ModuleHealth.of(descriptor, warnings.isEmpty() ? ServiceState.READY : ServiceState.DEGRADED,
                "Core API services online. Sections: " + CrownsAPI.getSections().size() + ".", warnings);
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public InboxManager getInboxManager() {
        return this.inboxManager;
    }
}
