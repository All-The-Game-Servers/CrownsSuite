package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.ModuleDescriptor;
import com.xkstudios.crowns.api.ModuleHealth;
import com.xkstudios.crowns.api.ServiceState;
import com.xkstudios.crowns.api.SuiteSection;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

public class CrownsTerrainPlugin extends JavaPlugin {
    private TerrainManager terrainManager;
    private TerrainMenuManager menuManager;
    private TerrainStudioManager studioManager;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.terrainManager = new TerrainManager(this);
        this.menuManager = new TerrainMenuManager(this);
        this.studioManager = new TerrainStudioManager(this);
        this.terrainManager.initialize();
        CrownsAPI.setTerrainProvider(this.terrainManager);
        CrownsAPI.setFloorRuntimeProvider(this.terrainManager);
        CrownsAPI.registerModule(new ModuleDescriptor(
                "terrain",
                "CrownsTerrain",
                "CrownsTerrain",
                this.getDescription().getVersion(),
                "1.4.0",
                List.of("CrownsAPI"),
                List.of("CrownsMMO"),
                List.of("terrain", "floor-runtime", "floor-readiness", "blueprints", "debug-maps", "structure-pipeline", "structure-studio")
        ), this::moduleHealth);
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
        Bukkit.getPluginManager().registerEvents(new TerrainStudioListener(this), this);
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
        if (CrownsAPI.getFloorRuntime() == this.terrainManager) {
            CrownsAPI.setFloorRuntimeProvider(null);
        }
        CrownsAPI.unregisterModule("terrain");
    }

    public TerrainManager getTerrainManager() {
        return this.terrainManager;
    }

    public TerrainMenuManager getMenuManager() {
        return this.menuManager;
    }

    public TerrainStudioManager getStudioManager() {
        return this.studioManager;
    }

    private ModuleHealth moduleHealth() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "terrain",
                "CrownsTerrain",
                "CrownsTerrain",
                this.getDescription().getVersion(),
                "1.4.0",
                List.of("CrownsAPI"),
                List.of("CrownsMMO"),
                List.of("terrain", "floor-runtime", "floor-readiness", "blueprints", "debug-maps", "structure-pipeline", "structure-studio")
        );
        List<String> warnings = new java.util.ArrayList<>();
        if (this.terrainManager == null) {
            warnings.add("TerrainManager is not initialized.");
        } else if (!this.terrainManager.getFloorRuntime(1).safeReady()) {
            warnings.add("Floor 1 is not SAFE_READY: " + this.terrainManager.getFloorRuntime(1).summary());
        }
        String summary = this.terrainManager == null
                ? "Terrain provider unavailable."
                : this.terrainManager.getFloorReadinessSummary(1);
        return ModuleHealth.of(descriptor, warnings.isEmpty() ? ServiceState.READY : ServiceState.DEGRADED, summary, warnings);
    }
}
