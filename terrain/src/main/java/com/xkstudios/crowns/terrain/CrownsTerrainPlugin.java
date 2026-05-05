package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.SuiteSection;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownsTerrainPlugin extends JavaPlugin {
    private TerrainManager terrainManager;
    private TerrainMenuManager menuManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.terrainManager = new TerrainManager(this);
        this.menuManager = new TerrainMenuManager(this);
        this.terrainManager.initialize();
        CrownsAPI.setTerrainProvider(this.terrainManager);
        CrownsAPI.registerSection(new SuiteSection(
                "terrain",
                "Terrain",
                Material.GRASS_BLOCK,
                "lowlight/terrain/hub",
                "crowns.terrain.use",
                player -> this.menuManager.openHub(player),
                player -> "Floor themes active"
        ));

        TerrainCommand command = new TerrainCommand(this);
        if (this.getCommand("crownsterrain") != null) {
            this.getCommand("crownsterrain").setExecutor(command);
            this.getCommand("crownsterrain").setTabCompleter(command);
        }
        Bukkit.getPluginManager().registerEvents(new TerrainMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TerrainSafetyListener(this), this);
        this.getLogger().info("CrownsTerrain enabled with hybrid route-first floor engine support.");
    }

    @Override
    public void onDisable() {
        if (this.terrainManager != null) {
            this.terrainManager.shutdown();
        }
        CrownsAPI.unregisterSection("terrain");
        if (CrownsAPI.getTerrain() == this.terrainManager) {
            CrownsAPI.setTerrainProvider(null);
        }
    }

    public TerrainManager getTerrainManager() {
        return this.terrainManager;
    }

    public TerrainMenuManager getMenuManager() {
        return this.menuManager;
    }
}
