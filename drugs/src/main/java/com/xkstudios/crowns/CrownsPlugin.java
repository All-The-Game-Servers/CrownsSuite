package com.xkstudios.crowns;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.ModuleDescriptor;
import com.xkstudios.crowns.api.ModuleHealth;
import com.xkstudios.crowns.api.ServiceState;
import com.xkstudios.crowns.api.SuiteSection;
import com.xkstudios.crowns.command.DrugCommand;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.drugs.DrugManager;
import com.xkstudios.crowns.drugs.gui.DrugMenuManager;
import com.xkstudios.crowns.drugs.listener.DrugListener;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

public class CrownsPlugin extends JavaPlugin {
    private DataManager dataManager;
    private DrugManager drugManager;
    private DrugMenuManager menuManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dataManager = CrownsAPI.getDataManager();
        if (this.dataManager == null) {
            this.getLogger().severe("CrownsAPI is required and did not expose a DataManager.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.drugManager = new DrugManager(this);
        this.menuManager = new DrugMenuManager(this);
        this.drugManager.initialize();

        CrownsAPI.registerModule(new ModuleDescriptor(
                "drugs",
                "CrownsDrugs",
                "CrownsDrugs",
                this.getDescription().getVersion(),
                "1.4.0",
                List.of("CrownsAPI"),
                List.of("CrownsEconomy"),
                List.of("drugs", "grow", "process", "consumables")
        ), this::moduleHealth);
        CrownsAPI.registerSection(new SuiteSection(
                "drugs",
                "Drugs",
                Material.BREWING_STAND,
                "lowlight/suite/drugs",
                "crowns.use",
                player -> this.menuManager.openHub(player),
                player -> "Grow, package, use, and sell stock for Crowns."
        ));

        DrugCommand command = new DrugCommand(this);
        if (this.getCommand("crownsdrugs") != null) {
            this.getCommand("crownsdrugs").setExecutor(command);
            this.getCommand("crownsdrugs").setTabCompleter(command);
        }
        Bukkit.getPluginManager().registerEvents(new DrugListener(this), this);
    }

    @Override
    public void onDisable() {
        CrownsAPI.unregisterModule("drugs");
        CrownsAPI.unregisterSection("drugs");
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public DrugManager getDrugManager() {
        return this.drugManager;
    }

    public DrugMenuManager getMenuManager() {
        return this.menuManager;
    }

    private ModuleHealth moduleHealth() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "drugs",
                "CrownsDrugs",
                "CrownsDrugs",
                this.getDescription().getVersion(),
                "1.4.0",
                List.of("CrownsAPI"),
                List.of("CrownsEconomy"),
                List.of("drugs", "grow", "process", "consumables")
        );
        List<String> warnings = new java.util.ArrayList<>();
        if (this.drugManager == null) {
            warnings.add("DrugManager is not initialized.");
        }
        if (CrownsAPI.getEconomy() == null) {
            warnings.add("CrownsEconomy is missing; buy/sell actions are blocked instead of using a second currency.");
        }
        String summary = CrownsAPI.getEconomy() == null
                ? "Drug production UI online; economy actions blocked until CrownsEconomy loads."
                : "Drug production, use, and Crowns payouts online.";
        return ModuleHealth.of(descriptor, warnings.isEmpty() ? ServiceState.READY : ServiceState.DEGRADED, summary, warnings);
    }
}
