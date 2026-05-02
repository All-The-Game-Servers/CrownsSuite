package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.MmoCommand;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.listener.MmoListener;
import com.xkstudios.crowns.mmo.MmoManager;
import com.xkstudios.crowns.mmo.floor.MmoFloorManager;
import com.xkstudios.crowns.mmo.gui.MmoMenuManager;
import com.xkstudios.crowns.mmo.item.MmoItemFactory;
import com.xkstudios.crowns.mmo.quest.MmoQuestManager;
import com.xkstudios.crowns.mmo.social.MmoGuildManager;
import com.xkstudios.crowns.mmo.social.MmoPartyManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private MmoManager mmoManager;
    private MmoFloorManager floorManager;
    private MmoItemFactory itemFactory;
    private MmoQuestManager questManager;
    private MmoPartyManager partyManager;
    private MmoGuildManager guildManager;
    private MmoMenuManager menuManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dataManager = CrownsAPI.getDataManager();
        if (this.dataManager == null) {
            this.getLogger().severe("CrownsAPI is required and did not expose a DataManager.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.mmoManager = new MmoManager(this);
        this.floorManager = new MmoFloorManager(this);
        this.itemFactory = new MmoItemFactory(this);
        this.questManager = new MmoQuestManager(this);
        this.partyManager = new MmoPartyManager(this);
        this.guildManager = new MmoGuildManager(this);
        this.menuManager = new MmoMenuManager(this);
        this.mmoManager.initialize();
        this.floorManager.initialize();
        this.itemFactory.initialize();
        this.questManager.initialize();
        this.guildManager.initialize();
        CrownsAPI.setMmoProvider(this.mmoManager);
        CrownsAPI.registerSection(new SuiteSection(
                "mmo",
                "MMO",
                Material.ENCHANTED_BOOK,
                null,
                "crowns.use",
                player -> this.menuManager.openHub(player),
                player -> this.mmoManager.getProfileSummary(player.getUniqueId(), player.getName())
        ));

        MmoCommand command = new MmoCommand(this);
        if (this.getCommand("crownsmmo") != null) {
            this.getCommand("crownsmmo").setExecutor(command);
            this.getCommand("crownsmmo").setTabCompleter(command);
        }
        Bukkit.getPluginManager().registerEvents(new MmoListener(this), this);
    }

    @Override
    public void onDisable() {
        CrownsAPI.unregisterSection("mmo");
        CrownsAPI.setMmoProvider(null);
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public MmoManager getMmoManager() {
        return this.mmoManager;
    }

    public MmoFloorManager getFloorManager() {
        return this.floorManager;
    }

    public MmoItemFactory getItemFactory() {
        return this.itemFactory;
    }

    public MmoQuestManager getQuestManager() {
        return this.questManager;
    }

    public MmoPartyManager getPartyManager() {
        return this.partyManager;
    }

    public MmoGuildManager getGuildManager() {
        return this.guildManager;
    }

    public MmoMenuManager getMenuManager() {
        return this.menuManager;
    }
}
