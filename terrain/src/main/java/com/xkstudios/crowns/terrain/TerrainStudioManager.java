package com.xkstudios.crowns.terrain;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class TerrainStudioManager {
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&()*+,-/:;<>?@[]^_{|}~";
    private static final int MAX_CAPTURE_BLOCKS = 40_000;
    private static final long CONFIRM_TIMEOUT_TICKS = 20L * 30L;

    private final CrownsTerrainPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, PendingPlacement> pendingPlacements = new HashMap<>();

    public TerrainStudioManager(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "structure_studio_wand");
    }

    public ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("CrownsTerrain Studio Wand", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Left click: set position 1", NamedTextColor.GRAY),
                Component.text("Right click: set position 2", NamedTextColor.GRAY)
        ));
        meta.getPersistentDataContainer().set(this.wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isStudioWand(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(this.wandKey, PersistentDataType.BYTE);
    }

    public void giveWand(Player player) {
        player.getInventory().addItem(this.createWand());
        player.sendMessage(Component.text("Studio wand added. Left click sets pos1, right click sets pos2.", NamedTextColor.GREEN));
    }

    public void setPosition(Player player, int index, Location location) {
        if (location == null || location.getWorld() == null) {
            player.sendMessage(Component.text("No valid block position found.", NamedTextColor.RED));
            return;
        }
        Selection selection = this.selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
        Location blockLocation = location.toBlockLocation();
        if (index == 1) {
            selection.pos1 = blockLocation;
        } else {
            selection.pos2 = blockLocation;
        }
        player.sendMessage(Component.text("Studio pos" + index + " set to " + summary(blockLocation) + ".", NamedTextColor.GREEN));
    }

    public void capture(Player player, String key) {
        key = normalizeKey(key);
        Selection selection = this.selections.get(player.getUniqueId());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            player.sendMessage(Component.text("Set pos1 and pos2 first with /cterrain studio pos1/pos2 or the studio wand.", NamedTextColor.RED));
            return;
        }
        if (selection.pos1.getWorld() == null || selection.pos2.getWorld() == null || !selection.pos1.getWorld().equals(selection.pos2.getWorld())) {
            player.sendMessage(Component.text("Both selection positions must be in the same world.", NamedTextColor.RED));
            return;
        }
        World world = selection.pos1.getWorld();
        int minX = Math.min(selection.pos1.getBlockX(), selection.pos2.getBlockX());
        int maxX = Math.max(selection.pos1.getBlockX(), selection.pos2.getBlockX());
        int minY = Math.min(selection.pos1.getBlockY(), selection.pos2.getBlockY());
        int maxY = Math.max(selection.pos1.getBlockY(), selection.pos2.getBlockY());
        int minZ = Math.min(selection.pos1.getBlockZ(), selection.pos2.getBlockZ());
        int maxZ = Math.max(selection.pos1.getBlockZ(), selection.pos2.getBlockZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_CAPTURE_BLOCKS) {
            player.sendMessage(Component.text("Selection is too large: " + volume + " blocks. Limit: " + MAX_CAPTURE_BLOCKS + ".", NamedTextColor.RED));
            return;
        }

        Map<String, Character> palette = new HashMap<>();
        List<CapturedBlock> blocks = new ArrayList<>();
        int nextSymbol = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    String blockData = block.getBlockData().getAsString();
                    Character symbol = palette.get(blockData);
                    if (symbol == null) {
                        if (nextSymbol >= SYMBOLS.length()) {
                            player.sendMessage(Component.text("Too many unique block states for .ctpl palette. Limit: " + SYMBOLS.length() + ".", NamedTextColor.RED));
                            return;
                        }
                        symbol = SYMBOLS.charAt(nextSymbol++);
                        palette.put(blockData, symbol);
                    }
                    blocks.add(new CapturedBlock(x - minX, y - minY, z - minZ, symbol));
                }
            }
        }
        if (blocks.isEmpty()) {
            player.sendMessage(Component.text("Selection contains no non-air blocks.", NamedTextColor.RED));
            return;
        }

        File output = new File(this.plugin.getTerrainManager().structureTemplateManager().customTemplateFolder(), key + ".ctpl");
        try {
            this.writeCapture(output, key, palette, blocks, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
            this.plugin.getTerrainManager().reload();
            player.sendMessage(Component.text("Captured '" + key + "' to " + output.getAbsolutePath() + " (" + blocks.size() + " blocks).", NamedTextColor.GREEN));
        } catch (IOException exception) {
            player.sendMessage(Component.text("Could not write template: " + exception.getMessage(), NamedTextColor.RED));
        }
    }

    public void preview(Player player, String key, int rotation, int seconds) {
        StructureTemplate template = this.template(player, key);
        if (template == null) {
            return;
        }
        Location origin = this.origin(player);
        if (origin == null || origin.getWorld() == null) {
            player.sendMessage(Component.text("No valid preview origin found.", NamedTextColor.RED));
            return;
        }
        List<Location> changed = new ArrayList<>();
        for (StructureTemplate.BlockEntry block : template.blocks()) {
            Location location = this.worldLocation(origin, template, block, rotation);
            player.sendBlockChange(location, block.blockData());
            changed.add(location);
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            for (Location location : changed) {
                if (player.isOnline() && location.getWorld() != null) {
                    player.sendBlockChange(location, location.getBlock().getBlockData());
                }
            }
        }, Math.max(1, seconds) * 20L);
        player.sendMessage(Component.text("Previewing '" + template.key() + "' for " + Math.max(1, seconds) + " seconds. No real blocks were changed.", NamedTextColor.GREEN));
    }

    public void place(Player player, String key, int rotation) {
        StructureTemplate template = this.template(player, key);
        if (template == null) {
            return;
        }
        Location origin = this.origin(player);
        if (origin == null || origin.getWorld() == null) {
            player.sendMessage(Component.text("No valid placement origin found.", NamedTextColor.RED));
            return;
        }
        this.pendingPlacements.put(player.getUniqueId(), new PendingPlacement(template, origin, Math.floorMod(rotation, 4)));
        player.sendMessage(Component.text("Placement staged for '" + template.key() + "' at " + summary(origin) + ". Run /cterrain studio confirm within 30 seconds.", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            PendingPlacement pending = this.pendingPlacements.get(player.getUniqueId());
            if (pending != null && pending.template() == template) {
                this.pendingPlacements.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Structure placement expired.", NamedTextColor.GRAY));
                }
            }
        }, CONFIRM_TIMEOUT_TICKS);
    }

    public void confirm(Player player) {
        PendingPlacement pending = this.pendingPlacements.remove(player.getUniqueId());
        if (pending == null) {
            player.sendMessage(Component.text("No pending structure placement to confirm.", NamedTextColor.RED));
            return;
        }
        int placed = 0;
        for (StructureTemplate.BlockEntry block : pending.template().blocks()) {
            Location location = this.worldLocation(pending.origin(), pending.template(), block, pending.rotation());
            location.getBlock().setBlockData(block.blockData(), false);
            placed++;
        }
        player.sendMessage(Component.text("Placed '" + pending.template().key() + "' (" + placed + " blocks).", NamedTextColor.GREEN));
    }

    public void cancel(Player player) {
        this.pendingPlacements.remove(player.getUniqueId());
        player.sendMessage(Component.text("Cancelled pending Structure Studio placement.", NamedTextColor.YELLOW));
    }

    private StructureTemplate template(Player player, String key) {
        StructureTemplate template = this.plugin.getTerrainManager().structureTemplateManager().get(key);
        if (template == null) {
            player.sendMessage(Component.text("Unknown structure '" + key + "'. Use /cterrain structure list.", NamedTextColor.RED));
        }
        return template;
    }

    private Location origin(Player player) {
        Block target = player.getTargetBlockExact(80);
        if (target != null) {
            return target.getLocation().add(0, 1, 0);
        }
        return player.getLocation().toBlockLocation();
    }

    private Location worldLocation(Location origin, StructureTemplate template, StructureTemplate.BlockEntry block, int rotation) {
        int[] rotated = template.rotate(block.x() - template.anchorX(), block.z() - template.anchorZ(), rotation);
        return new Location(origin.getWorld(), origin.getBlockX() + rotated[0], origin.getBlockY() + block.y() - template.anchorY(), origin.getBlockZ() + rotated[1]);
    }

    private void writeCapture(File output, String key, Map<String, Character> palette, List<CapturedBlock> blocks, int width, int height, int depth) throws IOException {
        output.getParentFile().mkdirs();
        Map<Character, String> reversePalette = new HashMap<>();
        for (Map.Entry<String, Character> entry : palette.entrySet()) {
            reversePalette.put(entry.getValue(), entry.getKey());
        }
        Map<Integer, Map<Integer, Map<Integer, Character>>> layers = new HashMap<>();
        for (CapturedBlock block : blocks) {
            layers.computeIfAbsent(block.y, ignored -> new HashMap<>())
                    .computeIfAbsent(block.z, ignored -> new HashMap<>())
                    .put(block.x, block.symbol);
        }

        List<String> lines = new ArrayList<>();
        lines.add("key: " + key);
        lines.add("anchor: " + (width / 2) + ",0," + (depth / 2));
        lines.add("palette:");
        for (int index = 0; index < SYMBOLS.length(); index++) {
            char symbol = SYMBOLS.charAt(index);
            String blockData = reversePalette.get(symbol);
            if (blockData != null) {
                lines.add(symbol + "=" + blockData);
            }
        }
        lines.add("layers:");
        for (int y = 0; y < height; y++) {
            lines.add("y=" + y);
            Map<Integer, Map<Integer, Character>> layer = layers.getOrDefault(y, Map.of());
            for (int z = 0; z < depth; z++) {
                Map<Integer, Character> row = layer.getOrDefault(z, Map.of());
                StringBuilder builder = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    builder.append(row.getOrDefault(x, '.'));
                }
                lines.add(builder.toString());
            }
        }
        Files.write(output.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static String normalizeKey(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_').replaceAll("[^a-z0-9_]", "");
        return normalized.isBlank() ? "studio_capture" : normalized;
    }

    private static String summary(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static final class Selection {
        private Location pos1;
        private Location pos2;
    }

    private record CapturedBlock(int x, int y, int z, char symbol) {
    }

    private record PendingPlacement(StructureTemplate template, Location origin, int rotation) {
    }
}
