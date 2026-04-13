package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EventsProvider;
import com.xkstudios.crowns.api.InboxProvider;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.EventsCommand;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.event.EventListener;
import com.xkstudios.crowns.event.EventManager;
import com.xkstudios.crowns.gui.EventMenuManager;
import com.xkstudios.crowns.listener.EventGuiListener;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private EventManager eventManager;
    private EconomyBridge economyBridge;
    private final InboxBridge inboxBridge = new InboxBridge();
    private EventMenuManager menuManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dataManager = CrownsAPI.getDataManager();
        if (this.dataManager == null) {
            this.getLogger().severe("CrownsAPI is required and did not expose a DataManager.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.economyBridge = new EconomyBridge();
        this.eventManager = new EventManager(this);
        this.menuManager = new EventMenuManager(this);
        this.eventManager.load();
        CrownsAPI.registerSection(new SuiteSection(
                "events",
                "Events",
                Material.DRAGON_HEAD,
                "lowlight/suite/events",
                "crowns.use",
                player -> this.menuManager.openHub(player),
                player -> this.eventManager.getStatusLabel() + ": " + this.eventManager.getMenuLabel()
        ));
        CrownsAPI.setEventsProvider(new EventsProvider() {
            @Override
            public String getDimensionLockMessage(World.Environment environment) {
                return eventManager.getDimensionLockMessage(environment);
            }

            @Override
            public String getActiveEventLabel() {
                return eventManager.getMenuLabel();
            }

            @Override
            public String getStatusLabel() {
                return eventManager.getStatusLabel();
            }

            @Override
            public String getPlayerProgressSummary(java.util.UUID playerId, String playerName) {
                return eventManager.getProgressSummary(playerId, playerName);
            }

            @Override
            public java.util.List<String> getLiveEventSummaries() {
                return eventManager.getLiveMomentSummaries();
            }
        });

        EventsCommand command = new EventsCommand(this);
        if (this.getCommand("crownsevents") != null) {
            this.getCommand("crownsevents").setExecutor(command);
            this.getCommand("crownsevents").setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(new EventListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EventGuiListener(this), this);
    }

    @Override
    public void onDisable() {
        CrownsAPI.setEventsProvider(null);
        CrownsAPI.unregisterSection("events");
        if (this.eventManager != null) {
            this.eventManager.shutdown();
        }
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public EventManager getEventManager() {
        return this.eventManager;
    }

    public EconomyBridge getEconomy() {
        return this.economyBridge;
    }

    public EventMenuManager getMenuManager() {
        return this.menuManager;
    }

    public InboxBridge getInboxManager() {
        return this.inboxBridge;
    }

    public static class EconomyBridge {
        public void deposit(Player player, long amount, String category, String detail) {
            if (player == null || amount <= 0L || CrownsAPI.getEconomy() == null) {
                return;
            }
            CrownsAPI.getEconomy().deposit(player.getUniqueId(), amount);
        }
    }

    public static class InboxBridge {
        public void push(java.util.UUID playerUuid, String playerName, String type, String title, String body) {
            InboxProvider provider = CrownsAPI.getInbox();
            if (provider != null && playerUuid != null) {
                provider.sendNotification(playerUuid, title, body == null ? "" : body);
            }
        }
    }
}
