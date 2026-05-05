package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
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
        if (args[0].equalsIgnoreCase("verify")) {
            int floor = args.length >= 3 && args[1].equalsIgnoreCase("floor") ? this.parseFloor(args, 2, 1) : this.parseFloor(args, 1, 1);
            this.sendVerification(sender, floor);
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
        if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
            int floor = this.parseFloor(args, 2, 1);
            var world = this.plugin.getTerrainManager().createFloorWorld(floor);
            sender.sendMessage(Component.text(world == null
                    ? "CrownsTerrain could not create Floor " + floor + ". Check console for world-generator errors."
                    : "CrownsTerrain loaded Floor " + floor + " world '" + world.getName() + "'. Existing chunks were not overwritten.",
                    world == null ? NamedTextColor.RED : NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("blueprint")) {
            int floor = this.parseFloor(args, 2, 1);
            this.plugin.getTerrainManager().prepareBlueprint(sender, floor);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("debugmaps")) {
            int floor = this.parseFloor(args, 2, 1);
            this.plugin.getTerrainManager().generateDebugMaps(sender, floor);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("generate")) {
            int floor = this.parseFloor(args, 2, 1);
            this.plugin.getTerrainManager().startGeneration(sender, floor);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("status")) {
            int floor = this.parseFloor(args, 2, 1);
            for (String line : this.plugin.getTerrainManager().getGenerationStatusLines(floor)) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("cancel")) {
            int floor = this.parseFloor(args, 2, 1);
            this.plugin.getTerrainManager().cancelGeneration(sender, floor);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("regenerate")) {
            sender.sendMessage(Component.text("Regeneration is intentionally guarded. CrownsTerrain will not delete or overwrite existing worlds automatically.", NamedTextColor.RED));
            sender.sendMessage(Component.text("To regenerate, stop the server, back up data, remove the target floor world folder manually, then run /cterrain admin generate <floor>.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("tp")) {
            String type = normalizeType(args[2]);
            int floor = this.parseFloor(args, 3, 1);
            String key = args.length >= 5 ? args[4] : null;
            this.teleportToPoint(sender, floor, type, key);
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
        sender.sendMessage(Component.text("Usage: /cterrain admin <reload|create|blueprint|debugmaps|generate|status|cancel|regenerate|list|locate|tp>", NamedTextColor.YELLOW));
        return true;
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage(Component.text("CrownsTerrain", NamedTextColor.GREEN)
                .append(Component.text(" provides hybrid blueprint floors, pregeneration, landmarks, and arena locations.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("Use /cterrain admin blueprint 1, /cterrain admin debugmaps 1, /cterrain admin generate 1, or /cterrain preview <floor>.", NamedTextColor.YELLOW));
        if (sender instanceof Player player) {
            this.plugin.getMenuManager().openHub(player);
        }
    }

    private void sendPreview(CommandSender sender, int floor) {
        String worldName = this.worldName(floor);
        sender.sendMessage(Component.text("Floor " + floor + " Theme: " + this.plugin.getTerrainManager().getFloorTheme(floor), NamedTextColor.AQUA));
        String placement = this.plugin.getTerrainManager().isHybridBlueprintFloor(floor)
                ? "hybrid blueprint"
                : this.plugin.getTerrainManager().isSetMapFloor(floor) ? "set-map pregenerated" : "seeded-random";
        sender.sendMessage(Component.text("Profile: " + this.plugin.getTerrainManager().getTerrainProfile(floor)
                + " | World size: " + this.plugin.getTerrainManager().getWorldSize(floor)
                + " | Placement: " + placement, NamedTextColor.GRAY));
        TerrainGenerationStatus status = this.plugin.getTerrainManager().getGenerationStatus(floor);
        if (this.plugin.getTerrainManager().isManagedGenerationFloor(floor)) {
            sender.sendMessage(Component.text("Generation: " + status.status() + " | " + status.progressSummary(), status.readyForPlayers() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
        if (this.plugin.getTerrainManager().isHybridBlueprintFloor(floor)) {
            for (String line : this.plugin.getTerrainManager().getBlueprintStatusLines(floor)) {
                sender.sendMessage(Component.text(line, NamedTextColor.DARK_AQUA));
            }
        }
        if (this.plugin.getTerrainManager().isFreshWorldRequired(floor)) {
            sender.sendMessage(Component.text("Fresh world required: create/use '" + worldName + "'. Existing floor worlds are not overwritten.", NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("Structure density: " + String.format("%.2f", this.plugin.getTerrainManager().getStructureDensity(floor))
                + " | Persisted points: " + this.plugin.getTerrainManager().countPersistedPoints(floor, worldName), NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("Regions: " + String.join(", ", this.plugin.getTerrainManager().getRegionNames(floor)), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Districts: " + String.join(", ", this.plugin.getTerrainManager().getDistrictNames(floor)), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Tree pools: " + this.plugin.getTerrainManager().getTreePoolSummary(floor), NamedTextColor.DARK_GREEN));
        sender.sendMessage(Component.text("Hydrology: " + this.plugin.getTerrainManager().getHydrologySummary(floor), NamedTextColor.AQUA));
        TerrainPoint arena = this.plugin.getTerrainManager().getBossArena(floor, worldName);
        sender.sendMessage(Component.text(arena == null ? "Arena: unavailable" : "Arena: " + arena.displayName() + " at " + arena.coordinateSummary(), NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("Villages: " + this.plugin.getTerrainManager().getVillages(floor, worldName).size(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Camps: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "camp").size()
                + " | Waystones: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "waystone").size()
                + " | Road markers: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "road_marker").size()
                + " | Shrines: " + this.plugin.getTerrainManager().getPoints(floor, worldName, "shrine").size(), NamedTextColor.GRAY));
    }

    private void sendVerification(CommandSender sender, int floor) {
        sender.sendMessage(Component.text("CrownsTerrain Verification: Floor " + floor, NamedTextColor.GOLD));
        for (String line : this.plugin.getTerrainManager().verifyFloor(floor)) {
            NamedTextColor color = line.startsWith("PASS") ? NamedTextColor.GREEN : line.startsWith("FAIL") ? NamedTextColor.RED : NamedTextColor.GRAY;
            sender.sendMessage(Component.text("- " + line, color));
        }
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
            sender.sendMessage(Component.text("- " + point.displayName() + " [" + point.key() + "]: " + point.coordinateSummary(), NamedTextColor.GRAY));
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
            sender.sendMessage(Component.text("- [" + point.type().replace('_', ' ') + "] " + point.displayName() + " [" + point.key() + "]: " + point.coordinateSummary(), NamedTextColor.GRAY));
        }
    }

    private void teleportToPoint(CommandSender sender, int floor, String type, String requestedKey) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use /cterrain admin tp. Console can use /cterrain admin locate instead.", NamedTextColor.RED));
            return;
        }
        String worldName = this.worldName(floor);
        List<TerrainPoint> points = this.plugin.getTerrainManager().getPoints(floor, worldName, type);
        if (points.isEmpty()) {
            sender.sendMessage(Component.text("No " + type.replace('_', ' ') + " points exist for Floor " + floor + ".", NamedTextColor.RED));
            return;
        }
        TerrainPoint target = null;
        if (requestedKey != null && !requestedKey.isBlank()) {
            String normalizedKey = requestedKey.toLowerCase().replace('-', '_');
            for (TerrainPoint point : points) {
                String pointKey = point.key().toLowerCase().replace('-', '_');
                if (pointKey.equals(normalizedKey) || pointKey.replace('_', '-').equals(requestedKey.toLowerCase())) {
                    target = point;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(Component.text("No " + type.replace('_', ' ') + " point key '" + requestedKey + "' exists. Available: " + this.keyList(points), NamedTextColor.RED));
                return;
            }
        } else if (points.size() == 1) {
            target = points.get(0);
        } else {
            sender.sendMessage(Component.text("Multiple " + type.replace('_', ' ') + " points exist. Add a key: " + this.keyList(points), NamedTextColor.YELLOW));
            return;
        }

        World world = this.plugin.getTerrainManager().createFloorWorld(floor);
        if (world == null) {
            sender.sendMessage(Component.text("Could not load Floor " + floor + " world '" + worldName + "'.", NamedTextColor.RED));
            return;
        }
        world.getChunkAt(target.x() >> 4, target.z() >> 4).load(true);
        int safeY = Math.min(world.getMaxHeight() - 2, Math.max(world.getMinHeight() + 2, world.getHighestBlockYAt(target.x(), target.z()) + 1));
        Location location = new Location(world, target.x() + 0.5D, safeY, target.z() + 0.5D, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(location);
        sender.sendMessage(Component.text("Teleported to " + target.displayName() + " [" + target.key() + "] in " + world.getName()
                + " at " + target.x() + ", " + safeY + ", " + target.z() + ".", NamedTextColor.GREEN));
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
        return this.plugin.getTerrainManager().getWorldName(floor);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return this.match(args[0], List.of("info", "preview", "villages", "verify", "admin"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return this.match(args[1], List.of("reload", "create", "blueprint", "debugmaps", "generate", "status", "cancel", "regenerate", "locate", "list", "tp"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("locate") || args[1].equalsIgnoreCase("tp"))) {
            return this.match(args[2], List.of("village", "camp", "landmark", "waystone", "road_marker", "shrine", "arena"));
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("villages")))
                || (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("generate") || args[1].equalsIgnoreCase("status") || args[1].equalsIgnoreCase("cancel")))
                || (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("blueprint") || args[1].equalsIgnoreCase("debugmaps")))
                || (args.length == 4 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("locate") || args[1].equalsIgnoreCase("tp")))) {
            return this.match(args[args.length - 1], List.of("1", "2", "3"));
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("tp")) {
            String type = normalizeType(args[2]);
            int floor = this.parseFloor(args, 3, 1);
            List<String> keys = new ArrayList<>();
            for (TerrainPoint point : this.plugin.getTerrainManager().getPoints(floor, this.worldName(floor), type)) {
                keys.add(point.key());
            }
            return this.match(args[4], keys);
        }
        return List.of();
    }

    private String keyList(List<TerrainPoint> points) {
        List<String> keys = new ArrayList<>();
        for (TerrainPoint point : points) {
            keys.add(point.key());
        }
        return String.join(", ", keys);
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
