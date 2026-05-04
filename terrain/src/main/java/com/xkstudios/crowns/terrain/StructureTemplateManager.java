package com.xkstudios.crowns.terrain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

public final class StructureTemplateManager {
    private static final List<String> BUNDLED_TEMPLATES = List.of(
            "spawn_plaza", "haven_house", "farm_plot", "market_stall", "notice_board",
            "watchtower", "waystone", "starter_shrine", "camp", "road_marker",
            "landmark_spire", "gate_arena_core", "gate_arch", "ruin_pillar",
            "gatehouse", "bridge", "town_hall", "mill", "terraced_farm",
            "hillside_house", "switchback_stair", "retaining_wall", "large_shrine",
            "giant_tree_base", "stream_camp", "ruined_gate_marker"
    );

    private final CrownsTerrainPlugin plugin;
    private final Map<String, StructureTemplate> templates = new HashMap<>();

    public StructureTemplateManager(CrownsTerrainPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.templates.clear();
        for (String key : BUNDLED_TEMPLATES) {
            String path = "structures/" + key + ".ctpl";
            try (InputStream stream = this.plugin.getResource(path)) {
                if (stream == null) {
                    this.plugin.getLogger().warning("[CrownsTerrain] Missing bundled structure template: " + path);
                    continue;
                }
                StructureTemplate template = this.parse(stream, key);
                this.templates.put(template.key(), template);
            } catch (IOException | IllegalArgumentException exception) {
                this.plugin.getLogger().warning("[CrownsTerrain] Could not load structure template '" + key + "': " + exception.getMessage());
            }
        }
    }

    public StructureTemplate get(String key) {
        return this.templates.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
    }

    public int count() {
        return this.templates.size();
    }

    private StructureTemplate parse(InputStream stream, String fallbackKey) throws IOException {
        String key = fallbackKey;
        int anchorX = 0;
        int anchorY = 0;
        int anchorZ = 0;
        boolean inPalette = false;
        boolean inLayers = false;
        int y = 0;
        Map<Character, Material> palette = new HashMap<>();
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
                        Material material = Material.matchMaterial(trimmed.substring(equals + 1).trim());
                        if (material != null) {
                            palette.put(symbol, material);
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
                        Material material = palette.get(symbol);
                        if (material != null && material != Material.AIR) {
                            blocks.add(new StructureTemplate.BlockEntry(x, y, z, material));
                        }
                    }
                    z++;
                }
            }
        }
        return new StructureTemplate(key, anchorX, anchorY, anchorZ, blocks);
    }
}
