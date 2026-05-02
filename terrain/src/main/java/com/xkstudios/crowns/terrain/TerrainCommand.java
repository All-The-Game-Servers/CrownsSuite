package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class TerrainCommand implements CommandExecutor, TabCompleter {
    private final CrownsTerrainPlugin plugin;

    public TerrainCommand(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            this.sendInfo(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("preview")) {
            int floor = this.parseFloor(args, 1, 1);
            this.sendPreview(sender, floor);
            return true;
        }
        if (args[0].equalsIgnoreCase("villages")) {
            int floor = this.parseFloor(args, 1, 1);
            this.sendPoints(sender, floor, "village");
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            return this.handleAdmin(sender, args);
        }
        if (sender instanceof Player player) {
            this.plugin.getMenuManager().openHub(player);
        } else {
            this.sendInfo(sender);
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crowns.terrain.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("You do not have permission to use terrain admin commands.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            this.plugin.getTerrainManager().reload();
            sender.sendMessage(Component.text("CrownsTerrain config and layout cache reloaded.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("locate")) {
            String type = normalizeType(args[2]);
            int floor = this.parseFloor(args, 3, 1);
            this.sendPoints(sender, floor, type);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("list")) {
            int floor = this.parseFloor(args, 2, 1);
            this.sendAllPoints(sender, floor);
            return true;
        }
        sender.sendMessage(Component.text("Usage: /cterrain admin <reload|list <floor>|locate <type> <floor>>", NamedTextColor.YELLOW));
        return true;
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage(Component.text("CrownsTerrain", NamedTextColor.GREEN)
                .append(Component.text(" provides hybrid floor terrain, villages, landmarks, and arena locations.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("Use /cterrain preview <floor> or /cterrain villages <floor>.", NamedTextColor.YELLOW));
        if (sender instanceof Player player) {
            this.plugin.getMenuManager().openHub(player);
        }
    }

    private void sendPreview(CommandSender sender, int floor) {
        String worldName = this.worldName(floor);
        sender.sendMessage(Component.text("Floor " + floor + " Theme: " + this.plugin.getTerrainManager().getFloorTheme(floor), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Profile: " + this.plugin.getTerrainManager().getTerrainProfile(floor)
                + " | World size: " + this.plugin.getTerrainManager().getWorldSize(floor)
                + " | Placement: seeded-random", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Structure density: " + String.format("%.2f", this.plugin.getTerrainManager().getStructureDensity(floor))
                + " | Persisted points: " + this.plugin.getTerrainManager().countPersistedPoints(floor, worldName), NamedTextColor.DARK_GRAY));
        TerrainPoint arena = this.plugin.getTerrainManager().getBossArena(floor, worldName);
        sender.sendMessage(Component.text(arena == null ? "Arena: unavailable" : "Arena: " + arena.displayName() + " at " + arena.coordinateSummary(), NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("Villages: " + this.plugin.getTerrainManager().getVillages(floor, worldName).size(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Camps: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "camp").size()
                + " | Waystones: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "waystone").size()
                + " | Road markers: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "road_marker").size()
                + " | Shrines: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "shrine").size(), NamedTextColor.GRAY));
    }

    private void sendPoints(CommandSender sender, int floor, String type) {
        String worldName = this.worldName(floor);
        String normalized = normalizeType(type);
        List<TerrainPoint> points = this.plugin.getTerrainManager().getPoints(floor, worldName, normalized);
        if (points.isEmpty()) {
            sender.sendMessage(Component.text("No " + normalized.replace('_', ' ') + " points configured for Floor " + floor + ".", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Floor " + floor + " " + normalized.replace('_', ' ') + " Points", NamedTextColor.GOLD));
        for (TerrainPoint point : points) {
            sender.sendMessage(Component.text("- " + point.displayName() + ": " + point.coordinateSummary(), NamedTextColor.GRAY));
        }
    }

    private void sendAllPoints(CommandSender sender, int floor) {
        String worldName = this.worldName(floor);
        List<TerrainPoint> points = this.plugin.getTerrainManager().getAllPoints(floor, worldName);
        if (points.isEmpty()) {
            sender.sendMessage(Component.text("No terrain points configured for Floor " + floor + ".", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Floor " + floor + " Terrain Points", NamedTextColor.GOLD));
        for (TerrainPoint point : points) {
            sender.sendMessage(Component.text("- [" + point.type().replace('_', ' ') + "] " + point.displayName() + ": " + point.coordinateSummary(), NamedTextColor.GRAY));
        }
    }

    private int parseFloor(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String worldName(int floor) {
        if (floor == 1) {
            return this.plugin.getConfig().getString("terrain.floor-1-world", "world");
        }
        return this.plugin.getConfig().getString("terrain.generated-world-prefix", "crowns_floor_") + floor;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return this.match(args[0], List.of("info", "preview", "villages", "admin"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return this.match(args[1], List.of("reload", "locate", "list"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("locate")) {
            return this.match(args[2], List.of("village", "camp", "landmark", "waystone", "road_marker", "shrine", "arena"));
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("villages")))
                || (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("list"))
                || (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("locate"))) {
            return this.match(args[args.length - 1], List.of("1", "2", "3"));
        }
        return List.of();
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.toLowerCase().replace('-', '_');
    }

    private List<String> match(String prefix, List<String> values) {
        String safe = prefix == null ? "" : prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(safe)) {
                result.add(value);
            }
        }
        return result;
    }
}
