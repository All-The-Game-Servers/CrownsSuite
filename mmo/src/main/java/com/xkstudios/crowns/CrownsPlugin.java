package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.MmoCommand;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.listener.MmoListener;
import com.xkstudios.crowns.mmo.MmoManager;
import com.xkstudios.crowns.mmo.gui.MmoMenuManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private MmoManager mmoManager;
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
        this.menuManager = new MmoMenuManager(this);
        this.mmoManager.initialize();
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

    public MmoMenuManager getMenuManager() {
        return this.menuManager;
    }
}
