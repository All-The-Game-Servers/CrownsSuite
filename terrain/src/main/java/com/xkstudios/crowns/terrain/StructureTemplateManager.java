package com.xkstudios.crowns.terrain;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public final class StructureTemplateManager {
    private static final List<String> BUNDLED_TEMPLATES = List.of(
            "spawn_plaza", "haven_house", "farm_plot", "market_stall", "notice_board",
            "watchtower", "waystone", "starter_shrine", "camp", "road_marker",
            "landmark_spire", "gate_arena_core", "gate_arch", "ruin_pillar",
            "gatehouse", "bridge", "town_hall", "mill", "terraced_farm",
            "hillside_house", "switchback_stair", "retaining_wall", "large_shrine",
            "giant_tree_base", "stream_camp", "ruined_gate_marker",
            "fountain_plaza", "haven_house_tall", "haven_house_garden", "blacksmith",
            "barn", "market_row", "gate_tower", "wall_fragment", "cliff_overlook",
            "river_bridge", "arena_threshold", "arena_staging",
            "fh_spawn_plaza_grand", "fh_town_hall_grand", "fh_market_hall",
            "fh_market_street", "fh_house_large_a", "fh_house_large_b",
            "fh_house_row", "fh_blacksmith_large", "fh_barn_large",
            "fh_farm_terrace_stamp", "fh_watchtower_tall", "fh_gatehouse_grand",
            "fh_shrine_grove", "fh_waystone_platform", "fh_starter_camp",
            "fh_road_straight", "fh_road_curve", "fh_bridge_stream",
            "fh_cliff_rocks", "fh_arena_approach", "fh_first_gate_platform"
    );

    private final CrownsTerrainPlugin plugin;
    private final Map<String, StructureTemplate> templates = new HashMap<>();
    private int bundledCount;
    private int customCount;

    public StructureTemplateManager(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.templates.clear();
        this.bundledCount = 0;
        this.customCount = 0;
        for (String key : BUNDLED_TEMPLATES) {
            String path = "structures/" + key + ".ctpl";
            try (InputStream stream = this.plugin.getResource(path)) {
                if (stream == null) {
                    this.plugin.getLogger().warning("[CrownsTerrain] Missing bundled structure template: " + path);
                    continue;
                }
                StructureTemplate template = this.parse(stream, key);
                this.templates.put(template.key(), template);
                this.bundledCount++;
            } catch (IOException | IllegalArgumentException exception) {
                this.plugin.getLogger().warning("[CrownsTerrain] Could not load structure template '" + key + "': " + exception.getMessage());
            }
        }
        this.loadCustomTemplates();
    }

    public StructureTemplate get(String key) {
        return this.templates.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
    }

    public int count() {
        return this.templates.size();
    }

    public int bundledCount() {
        return this.bundledCount;
    }

    public int customCount() {
        return this.customCount;
    }

    public File customTemplateFolder() {
        return new File(this.plugin.getDataFolder(), "structures");
    }

    public List<String> keys() {
        return this.templates.keySet().stream().sorted().toList();
    }

    public Collection<StructureTemplate> templates() {
        return this.templates.values().stream()
                .sorted(Comparator.comparing(StructureTemplate::key))
                .toList();
    }

    private void loadCustomTemplates() {
        File folder = this.customTemplateFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            this.plugin.getLogger().warning("[CrownsTerrain] Could not create custom structure folder: " + folder.getAbsolutePath());
            return;
        }
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".ctpl"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try (InputStream stream = new FileInputStream(file)) {
                String fallbackKey = file.getName().substring(0, file.getName().length() - ".ctpl".length()).toLowerCase(Locale.ROOT);
                StructureTemplate template = this.parse(stream, fallbackKey);
                this.templates.put(template.key(), template);
                this.customCount++;
            } catch (IOException | IllegalArgumentException exception) {
                this.plugin.getLogger().warning("[CrownsTerrain] Could not load custom structure template '" + file.getName() + "': " + exception.getMessage());
            }
        }
    }

    private StructureTemplate parse(InputStream stream, String fallbackKey) throws IOException {
        String key = fallbackKey;
        int anchorX = 0;
        int anchorY = 0;
        int anchorZ = 0;
        boolean inPalette = false;
        boolean inLayers = false;
        int y = 0;
        Map<Character, BlockData> palette = new HashMap<>();
        List<StructureTemplate.BlockEntry> blocks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int z = 0;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("key:")) {
                    key = trimmed.substring("key:".length()).trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                if (trimmed.startsWith("anchor:")) {
                    String[] parts = trimmed.substring("anchor:".length()).trim().split(",");
                    if (parts.length == 3) {
                        anchorX = Integer.parseInt(parts[0].trim());
                        anchorY = Integer.parseInt(parts[1].trim());
                        anchorZ = Integer.parseInt(parts[2].trim());
                    }
                    continue;
                }
                if (trimmed.equalsIgnoreCase("palette:")) {
                    inPalette = true;
                    inLayers = false;
                    continue;
                }
                if (trimmed.equalsIgnoreCase("layers:")) {
                    inPalette = false;
                    inLayers = true;
                    continue;
                }
                if (inPalette) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        char symbol = trimmed.charAt(0);
                        BlockData blockData = parseBlockData(trimmed.substring(equals + 1).trim());
                        if (blockData != null) {
                            palette.put(symbol, blockData);
                        }
                    }
                    continue;
                }
                if (inLayers) {
                    if (trimmed.startsWith("y=")) {
                        y = Integer.parseInt(trimmed.substring(2).trim());
                        z = 0;
                        continue;
                    }
                    for (int x = 0; x < line.length(); x++) {
                        char symbol = line.charAt(x);
                        if (symbol == '.') {
                            continue;
                        }
                        BlockData blockData = palette.get(symbol);
                        if (blockData != null && blockData.getMaterial() != Material.AIR) {
                            blocks.add(new StructureTemplate.BlockEntry(x, y, z, blockData.getMaterial(), blockData));
                        }
                    }
                    z++;
                }
            }
        }
        return new StructureTemplate(key, anchorX, anchorY, anchorZ, blocks);
    }

    private static BlockData parseBlockData(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Material material = Material.matchMaterial(trimmed);
        if (material != null) {
            return Bukkit.createBlockData(material);
        }
        try {
            return Bukkit.createBlockData(trimmed);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
