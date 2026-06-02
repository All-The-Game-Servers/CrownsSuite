package com.xkstudios.crowns.swords;

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

public class SwordProfileManager {
    public static final String[] STARTER_SKILLS = {
            "linear",
            "horizontal_arc",
            "starburst_step",
            "guard_breaker",
            "aegis_parry",
            "whirling_edge"
    };

    private final CrownsSwordsPlugin plugin;
    private final File file;
    private final Map<UUID, SwordProfile> profiles = new HashMap<>();
    private FileConfiguration data;

    public SwordProfileManager(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }
        this.data = YamlConfiguration.loadConfiguration(this.file);
    }

    public SwordProfile get(UUID playerId) {
        return this.profiles.computeIfAbsent(playerId, this::loadProfile);
    }

    public void saveAll() {
        for (SwordProfile profile : this.profiles.values()) {
            this.save(profile);
        }
        try {
            this.data.save(this.file);
        } catch (IOException exception) {
            this.plugin.getLogger().warning("Failed to save sword profiles: " + exception.getMessage());
        }
    }

    public void save(SwordProfile profile) {
        String path = "players." + profile.playerId();
        this.data.set(path + ".learned", profile.learnedSkills().stream().toList());
        this.data.set(path + ".bindings", null);
        for (Map.Entry<String, String> entry : profile.bindings().entrySet()) {
            this.data.set(path + ".bindings." + entry.getKey(), entry.getValue());
        }
    }

    public void bindDefaults(UUID playerId) {
        SwordProfile profile = this.get(playerId);
        for (Map.Entry<String, String> entry : profile.bindings().entrySet()) {
            GestureSequence sequence = SwordGestures.fromKey(entry.getKey());
            if (sequence != null && CrownsAPI.getActionInputService() != null) {
                CrownsAPI.getActionInputService().bind(playerId, "swords:" + entry.getValue(), sequence);
            }
        }
    }

    public void rebind(UUID playerId, String gestureKey, String skillKey) {
        SwordProfile profile = this.get(playerId);
        profile.learnedSkills().add(skillKey);
        profile.bindings().put(gestureKey, skillKey);
        GestureSequence sequence = SwordGestures.fromKey(gestureKey);
        if (sequence != null && CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().bind(playerId, "swords:" + skillKey, sequence);
        }
        this.save(profile);
    }

    private SwordProfile loadProfile(UUID playerId) {
        SwordProfile profile = new SwordProfile(playerId);
        String path = "players." + playerId;
        List<String> learned = this.data.getStringList(path + ".learned");
        if (learned.isEmpty()) {
            profile.learnedSkills().addAll(List.of(STARTER_SKILLS));
            profile.bindings().put("SNEAK_RIGHT_CLICK", "linear");
            profile.bindings().put("SNEAK_LEFT_CLICK", "horizontal_arc");
            profile.bindings().put("SNEAK_SWAP_HAND", "starburst_step");
            profile.bindings().put("SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK", "guard_breaker");
            profile.bindings().put("RIGHT_CLICK", "aegis_parry");
            profile.bindings().put("LEFT_CLICK", "whirling_edge");
            return profile;
        }
        for (String skill : learned) {
            if (skill != null && !skill.isBlank()) {
                profile.learnedSkills().add(skill.toLowerCase(Locale.ROOT));
            }
        }
        ConfigurationSection section = this.data.getConfigurationSection(path + ".bindings");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String skill = section.getString(key);
                if (skill != null && !skill.isBlank()) {
                    profile.bindings().put(key.toUpperCase(Locale.ROOT), skill.toLowerCase(Locale.ROOT));
                }
            }
        }
        return profile;
    }
}
