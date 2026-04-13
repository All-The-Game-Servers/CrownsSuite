package com.xkstudios.crowns;

import com.xkstudios.crowns.analytics.EconomyLedgerManager;
import com.xkstudios.crowns.analytics.PlaytimeManager;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyLedgerProvider;
import com.xkstudios.crowns.api.InboxProvider;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.AdminCommand;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.listener.AdminPlayerListener;
import com.xkstudios.crowns.listener.ModerationListener;
import com.xkstudios.crowns.moderation.ModerationManager;
import com.xkstudios.crowns.playerstate.AfkManager;
import com.xkstudios.crowns.util.EntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private ModerationManager moderationManager;
    private EconomyLedgerManager economyLedgerManager;
    private PlaytimeManager playtimeManager;
    private AfkManager afkManager;
    private EntityManager entityManager;
    private final InboxBridge inboxBridge = new InboxBridge();
    private final ShopGuardBridge shopGuardBridge = new ShopGuardBridge();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dataManager = CrownsAPI.getDataManager();
        if (this.dataManager == null) {
            this.getLogger().severe("CrownsAPI is required and did not expose a DataManager.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.moderationManager = new ModerationManager(this);
        this.economyLedgerManager = new EconomyLedgerManager(this);
        this.playtimeManager = new PlaytimeManager(this);
        this.afkManager = new AfkManager();
        this.entityManager = new EntityManager(this);

        this.moderationManager.load();
        CrownsAPI.registerSection(new SuiteSection(
                "admin",
                "Admin",
                Material.IRON_SWORD,
                "lowlight/suite/admin",
                "crowns.mod",
                player -> this.moderationManager.openStaffHub(player),
                player -> this.moderationManager.getOpenReports().isEmpty()
                        ? "Moderation, analytics, and staff tools."
                        : "Open reports: " + this.moderationManager.getOpenReports().size()
        ));
        CrownsAPI.setEconomyLedgerProvider(new EconomyLedgerProvider() {
            @Override
            public void recordSource(java.util.UUID playerUuid, String playerName, String category, long amount, String detail) {
                economyLedgerManager.recordSource(playerUuid, playerName, category, amount, detail);
            }

            @Override
            public void recordSink(java.util.UUID playerUuid, String playerName, String category, long amount, String detail) {
                economyLedgerManager.recordSink(playerUuid, playerName, category, amount, detail);
            }

            @Override
            public void recordServerSink(String category, long amount, String detail) {
                economyLedgerManager.recordServerSink(category, amount, detail);
            }
        });

        AdminCommand command = new AdminCommand(this);
        if (this.getCommand("crownsadmin") != null) {
            this.getCommand("crownsadmin").setExecutor(command);
            this.getCommand("crownsadmin").setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(new ModerationListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AdminPlayerListener(this), this);

        int playtimeMinutes = Math.max(1, this.getConfig().getInt("analytics.playtime.snapshot-interval-minutes", 5));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.playtimeManager::flushActiveSessions,
                playtimeMinutes * 20L * 60L, playtimeMinutes * 20L * 60L);

        int ledgerHours = Math.max(1, this.getConfig().getInt("analytics.economy.prune-interval-hours", 12));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.economyLedgerManager::pruneOldEntries,
                ledgerHours * 20L * 60L * 60L, ledgerHours * 20L * 60L * 60L);
    }

    @Override
    public void onDisable() {
        CrownsAPI.setEconomyLedgerProvider(null);
        CrownsAPI.unregisterSection("admin");
        if (this.playtimeManager != null) {
            this.playtimeManager.closeAllSessions();
        }
        if (this.entityManager != null) {
            this.entityManager.shutdown();
        }
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public ModerationManager getModerationManager() {
        return this.moderationManager;
    }

    public EconomyLedgerManager getEconomyLedgerManager() {
        return this.economyLedgerManager;
    }

    public PlaytimeManager getPlaytimeManager() {
        return this.playtimeManager;
    }

    public AfkManager getAfkManager() {
        return this.afkManager;
    }

    public EntityManager getEntityManager() {
        return this.entityManager;
    }

    public InboxBridge getInboxManager() {
        return this.inboxBridge;
    }

    public ShopGuardBridge getShopManager() {
        return this.shopGuardBridge;
    }

    public static class InboxBridge {
        public void push(java.util.UUID playerUuid, String playerName, String type, String title, String body) {
            InboxProvider provider = CrownsAPI.getInbox();
            if (provider != null && playerUuid != null) {
                provider.sendNotification(playerUuid, title, body == null ? "" : body);
            }
        }
    }

    public static class ShopGuardBridge {
        public Object getAt(String key) {
            return null;
        }
    }
}
