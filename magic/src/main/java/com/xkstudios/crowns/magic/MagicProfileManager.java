package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.action.GestureSequence;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class MagicProfileManager {
    public static final String[] STARTER_SPELLS = {
            "starlight_flicker",
            "ember_bolt",
            "aether_step",
            "verdant_mend",
            "arcane_ward",
            "gravity_snare"
    };

    private final CrownsMagicPlugin plugin;
    private final File file;
    private final Map<UUID, MagicProfile> profiles = new HashMap<>();
    private FileConfiguration data;

    public MagicProfileManager(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }
        this.data = YamlConfiguration.loadConfiguration(this.file);
    }

    public MagicProfile get(UUID playerId) {
        return this.profiles.computeIfAbsent(playerId, this::loadProfile);
    }

    public void saveAll() {
        for (MagicProfile profile : this.profiles.values()) {
            this.save(profile);
        }
        try {
            this.data.save(this.file);
        } catch (IOException exception) {
            this.plugin.getLogger().warning("Failed to save magic profiles: " + exception.getMessage());
        }
    }

    public void save(MagicProfile profile) {
        String path = "players." + profile.playerId();
        this.data.set(path + ".learned", profile.learnedSpells().stream().toList());
        this.data.set(path + ".bindings", null);
        for (Map.Entry<String, String> entry : profile.bindings().entrySet()) {
            this.data.set(path + ".bindings." + entry.getKey(), entry.getValue());
        }
    }

    public void bindDefaults(UUID playerId) {
        MagicProfile profile = this.get(playerId);
        for (Map.Entry<String, String> entry : profile.bindings().entrySet()) {
            GestureSequence sequence = MagicGestures.fromKey(entry.getKey());
            if (sequence != null) {
                CrownsAPI.getActionInputService().bind(playerId, "magic:" + entry.getValue(), sequence);
            }
        }
    }

    public void rebind(UUID playerId, String gestureKey, String spellKey) {
        MagicProfile profile = this.get(playerId);
        profile.learnedSpells().add(spellKey);
        profile.bindings().put(gestureKey, spellKey);
        GestureSequence sequence = MagicGestures.fromKey(gestureKey);
        if (sequence != null && CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().bind(playerId, "magic:" + spellKey, sequence);
        }
        this.save(profile);
    }

    private MagicProfile loadProfile(UUID playerId) {
        MagicProfile profile = new MagicProfile(playerId);
        String path = "players." + playerId;
        List<String> learned = this.data.getStringList(path + ".learned");
        if (learned.isEmpty()) {
            profile.learnedSpells().addAll(List.of(STARTER_SPELLS));
            profile.bindings().put("SNEAK_RIGHT_CLICK", "ember_bolt");
            profile.bindings().put("SNEAK_LEFT_CLICK", "starlight_flicker");
            profile.bindings().put("SNEAK_SWAP_HAND", "aether_step");
            profile.bindings().put("SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK", "arcane_ward");
            profile.bindings().put("RIGHT_CLICK", "verdant_mend");
            profile.bindings().put("LEFT_CLICK", "gravity_snare");
            return profile;
        }
        for (String spell : learned) {
            if (spell != null && !spell.isBlank()) {
                profile.learnedSpells().add(spell.toLowerCase(Locale.ROOT));
            }
        }
        ConfigurationSection section = this.data.getConfigurationSection(path + ".bindings");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String spell = section.getString(key);
                if (spell != null && !spell.isBlank()) {
                    profile.bindings().put(key.toUpperCase(Locale.ROOT), spell.toLowerCase(Locale.ROOT));
                }
            }
        }
        return profile;
    }
}
