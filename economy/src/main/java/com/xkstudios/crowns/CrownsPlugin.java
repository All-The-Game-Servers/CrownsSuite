package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyProvider;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.EconomyCommand;
import com.xkstudios.crowns.economy.CoinflipManager;
import com.xkstudios.crowns.economy.ContractManager;
import com.xkstudios.crowns.economy.DemandManager;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.economy.EconomyManager;
import com.xkstudios.crowns.economy.JobManager;
import com.xkstudios.crowns.economy.LotteryManager;
import com.xkstudios.crowns.economy.SlotsManager;
import com.xkstudios.crowns.gui.MenuManager;
import com.xkstudios.crowns.inbox.InboxManager;
import com.xkstudios.crowns.listener.EarningListener;
import com.xkstudios.crowns.listener.GUIListener;
import com.xkstudios.crowns.listener.EconomyPlayerListener;
import com.xkstudios.crowns.listener.QoLListener;
import com.xkstudios.crowns.market.AuctionManager;
import com.xkstudios.crowns.market.PermanentStallManager;
import com.xkstudios.crowns.util.AntiExploitManager;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private InboxManager inboxManager;
    private AntiExploitManager antiExploit;
    private EconomyLedgerBridge economyLedgerBridge;
    private EventsBridge eventsBridge;
    private EconomyManager economyManager;
    private DemandManager demandManager;
    private ContractManager contractManager;
    private JobManager jobManager;
    private LotteryManager lotteryManager;
    private CoinflipManager coinflipManager;
    private SlotsManager slotsManager;
    private AuctionManager auctionManager;
    private PermanentStallManager stallManager;
    private MenuManager menuManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dataManager = CrownsAPI.getDataManager();
        if (this.dataManager == null) {
            getLogger().severe("CrownsAPI is required and did not expose a DataManager.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.dataManager.setStartingBalance(this.getConfig().getLong("economy.starting-balance", 500L));
        this.inboxManager = new InboxManager(this, this.dataManager);
        this.antiExploit = new AntiExploitManager(this);
        this.economyLedgerBridge = new EconomyLedgerBridge();
        this.eventsBridge = new EventsBridge();
        this.economyManager = new EconomyManager(this);
        this.demandManager = new DemandManager(this);
        this.contractManager = new ContractManager(this);
        this.jobManager = new JobManager(this);
        this.lotteryManager = new LotteryManager(this);
        this.coinflipManager = new CoinflipManager(this);
        this.slotsManager = new SlotsManager(this);
        this.auctionManager = new AuctionManager(this);
        this.stallManager = new PermanentStallManager(this);
        this.menuManager = new MenuManager(this);
        this.auctionManager.load();
        this.demandManager.initialize();
        this.contractManager.initialize();
        this.stallManager.load();
        this.jobManager.initialize();
        CrownsAPI.setEconomyProvider(new EconomyProvider() {
            @Override
            public long getBalance(UUID player) {
                return dataManager.getOrCreate(player, "").getBalance();
            }

            @Override
            public boolean withdraw(UUID player, long amount) {
                return economyManager.withdraw(player, dataManager.getExistingOrCreate(player, "").getName(), amount, null, null);
            }

            @Override
            public void deposit(UUID player, long amount) {
                economyManager.deposit(player, dataManager.getExistingOrCreate(player, "").getName(), amount, null, null);
            }

            @Override
            public String formatCurrency(long pennies) {
                return Currency.format(pennies);
            }
        });
        CrownsAPI.registerSection(new SuiteSection(
                "economy",
                "Economy",
                Material.GOLD_INGOT,
                "lowlight/suite/economy",
                "crowns.use",
                player -> this.menuManager.openMainMenu(player),
                player -> {
                    int unread = this.inboxManager.getUnreadCount(player.getUniqueId());
                    return unread > 0 ? "Inbox: " + unread + " unread" : this.demandManager.getSuiteSummary() + " " + this.contractManager.getSummary();
                }
        ));
        EconomyCommand command = new EconomyCommand(this);
        if (this.getCommand("crownseconomy") != null) {
            this.getCommand("crownseconomy").setExecutor(command);
            this.getCommand("crownseconomy").setTabCompleter(command);
        }
        Bukkit.getPluginManager().registerEvents(new EarningListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EconomyPlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        Bukkit.getPluginManager().registerEvents(new QoLListener(this), this);
        int saveMinutes = this.getConfig().getInt("data.autosave-minutes", 5);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.dataManager::flushAll, saveMinutes * 20L * 60L, saveMinutes * 20L * 60L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.auctionManager::checkExpired, 6000L, 6000L);
        int marketRefreshHours = Math.max(1, this.getConfig().getInt("demand.refresh-hours", 12));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.demandManager::refreshMarket, marketRefreshHours * 20L * 60L * 60L, marketRefreshHours * 20L * 60L * 60L);
        if (this.getConfig().getBoolean("salary.enabled", true)) {
            long interval = (long) this.getConfig().getInt("salary.interval-minutes", 30) * 20L * 60L;
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (this.antiExploit.isAfk(player.getUniqueId())) {
                        continue;
                    }
                    var pd = this.dataManager.getOrCreate(player.getUniqueId(), player.getName());
                    long now = System.currentTimeMillis();
                    if (now - pd.getLastSalaryTime() < this.getConfig().getInt("salary.interval-minutes", 30) * 60000L) {
                        continue;
                    }
                    pd.setLastSalaryTime(now);
                    this.economyManager.reward(player, this.getConfig().getLong("salary.amount", 50L), "Salary");
                }
            }, interval, interval);
        }
        if (this.getConfig().getBoolean("jobs.enabled", true)) {
            int hours = this.getConfig().getInt("jobs.refresh-hours", 24);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.jobManager::refreshJobs, (long) hours * 20L * 60L * 60L, (long) hours * 20L * 60L * 60L);
        }
        if (this.getConfig().getBoolean("lottery.enabled", true)) {
            long drawHours = Math.max(1L, this.getConfig().getLong("lottery.draw-interval-hours", 168L));
            long ticks = drawHours * 20L * 60L * 60L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this.lotteryManager::drawWinner, ticks, ticks);
        }
    }

    @Override
    public void onDisable() {
        if (CrownsAPI.getEconomy() != null) {
            CrownsAPI.setEconomyProvider(null);
        }
        CrownsAPI.unregisterSection("economy");
        if (this.dataManager != null) {
            this.dataManager.flushAll();
        }
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public InboxManager getInboxManager() {
        return inboxManager;
    }

    public AntiExploitManager getAntiExploit() {
        return antiExploit;
    }

    public EconomyLedgerBridge getEconomyLedgerManager() {
        return economyLedgerBridge;
    }

    public EventsBridge getEventManager() {
        return eventsBridge;
    }

    public EconomyManager getEconomy() {
        return economyManager;
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public LotteryManager getLotteryManager() {
        return lotteryManager;
    }

    public CoinflipManager getCoinflipManager() {
        return coinflipManager;
    }

    public SlotsManager getSlotsManager() {
        return slotsManager;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public DemandManager getDemandManager() {
        return demandManager;
    }

    public ContractManager getContractManager() {
        return contractManager;
    }

    public PermanentStallManager getStallManager() {
        return stallManager;
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }
}
