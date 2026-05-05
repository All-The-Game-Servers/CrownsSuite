package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.TerrainPoint;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public class TerrainMenuManager {
    private final CrownsTerrainPlugin plugin;

    public TerrainMenuManager(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("terrain-hub", 54, Component.text("CrownsTerrain", NamedTextColor.GREEN));
        inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.GRASS_BLOCK, "Hybrid Floor Engine", NamedTextColor.GREEN, List.of(
                Component.text("Admins precompute intent, debug maps, and route chunks.", NamedTextColor.GRAY),
                Component.text("Chunk rendering stays pure and Paper-safe.", NamedTextColor.DARK_GRAY)
        ), "lowlight/terrain/hub"));
        inventory.setItem(12, CrownsAPI.getSuiteGui().info(Material.OAK_DOOR, "Custom Villages", NamedTextColor.GOLD, List.of(
                Component.text("Code-authored floor settlements.", NamedTextColor.GRAY),
                Component.text("Safe, useful, and NPC-ready by default.", NamedTextColor.YELLOW)
        ), "lowlight/terrain/village"));
        inventory.setItem(14, CrownsAPI.getSuiteGui().info(Material.END_CRYSTAL, "Boss Arenas", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Terrain can provide persistent arena locations.", NamedTextColor.GRAY),
                Component.text("CrownsMMO uses them when choosing boss arenas.", NamedTextColor.GRAY)
        ), "lowlight/terrain/arena"));
        inventory.setItem(16, CrownsAPI.getSuiteGui().info(Material.LODESTONE, "Landmarks", NamedTextColor.AQUA, List.of(
                Component.text("Waystones and visual navigation anchors.", NamedTextColor.GRAY)
        ), "lowlight/terrain/landmark"));
        inventory.setItem(20, CrownsAPI.getSuiteGui().info(Material.CAMPFIRE, "Living Floor 1", NamedTextColor.GREEN, List.of(
                Component.text("Camps, road markers, shrines, and richer villages.", NamedTextColor.GRAY),
                Component.text("Focused on safe starter-floor identity.", NamedTextColor.YELLOW)
        ), "lowlight/terrain/camp"));
        int slot = 28;
        for (int floor = 1; floor <= 3; floor++) {
            String worldName = this.plugin.getTerrainManager().getWorldName(floor);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Theme: " + this.plugin.getTerrainManager().getFloorTheme(floor), NamedTextColor.GRAY));
            lore.add(Component.text("Profile: " + this.plugin.getTerrainManager().getTerrainProfile(floor), NamedTextColor.DARK_GRAY));
            TerrainGenerationStatus status = this.plugin.getTerrainManager().getGenerationStatus(floor);
            if (this.plugin.getTerrainManager().isManagedGenerationFloor(floor)) {
                lore.add(Component.text("Generation: " + status.status(), status.readyForPlayers() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            if (this.plugin.getTerrainManager().isHybridBlueprintFloor(floor)) {
                for (String line : this.plugin.getTerrainManager().getBlueprintStatusLines(floor)) {
                    if (line.startsWith("Blueprint:") || line.startsWith("Metrics:")) {
                        lore.add(Component.text(line, NamedTextColor.DARK_AQUA));
                    }
                }
            }
            lore.add(Component.text("World size: " + this.plugin.getTerrainManager().getWorldSize(floor) + " x " + this.plugin.getTerrainManager().getWorldSize(floor), NamedTextColor.GRAY));
            lore.add(Component.text("Villages: " + this.plugin.getTerrainManager().getVillages(floor, worldName).size(), NamedTextColor.YELLOW));
            lore.add(Component.text("Living points: " + this.plugin.getTerrainManager().getLivingPoints(floor, worldName).size(), NamedTextColor.GREEN));
            TerrainPoint arena = this.plugin.getTerrainManager().getBossArena(floor, worldName);
            lore.add(Component.text(arena == null ? "Arena: unavailable" : "Arena: " + arena.coordinateSummary(), NamedTextColor.LIGHT_PURPLE));
            inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.MAP, "Floor " + floor, NamedTextColor.AQUA, lore, "lowlight/terrain/floor_" + floor));
            slot += 2;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().backToHomeButton());
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }
}
