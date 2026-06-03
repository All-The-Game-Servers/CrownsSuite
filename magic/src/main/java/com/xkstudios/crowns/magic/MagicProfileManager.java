package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.action.AbilityRank;
import com.xkstudios.crowns.api.action.GestureSequence;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
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
    public static final Map<String, String> SCHOOLS = Map.of(
            "elemental", "Elemental",
            "restoration", "Restoration",
            "astral", "Astral"
    );
    private static final int[] RANK_XP = {0, 0, 100, 250, 450, 700};
    private static final Map<String, Integer> UNLOCK_RANKS = new LinkedHashMap<>();

    static {
        for (String spell : STARTER_SPELLS) {
            UNLOCK_RANKS.put(spell, 1);
        }
        UNLOCK_RANKS.put("starfall_spark", 2);
        UNLOCK_RANKS.put("moonlit_veil", 3);
        UNLOCK_RANKS.put("astral_lance", 4);
    }

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
        this.data.set(path + ".rank", profile.rank());
        this.data.set(path + ".xp", profile.xp());
        this.data.set(path + ".school-xp", null);
        for (Map.Entry<String, Integer> entry : profile.schoolXp().entrySet()) {
            this.data.set(path + ".school-xp." + entry.getKey(), Math.max(0, entry.getValue()));
        }
        this.data.set(path + ".daily.date", profile.dailyDate());
        this.data.set(path + ".daily.casts", profile.dailyCasts());
        this.data.set(path + ".daily.hits", profile.dailyHits());
        this.data.set(path + ".daily.support", profile.dailySupport());
        this.data.set(path + ".daily.completed", profile.completedDaily().stream().toList());
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

    public int requiredRank(String spellKey) {
        return UNLOCK_RANKS.getOrDefault(spellKey, 1);
    }

    public boolean isUnlocked(UUID playerId, String spellKey) {
        MagicSpell spell = this.plugin.spells().spell(spellKey);
        if (spell != null) {
            return this.isUnlocked(playerId, spell);
        }
        return this.get(playerId).rank() >= this.requiredRank(spellKey);
    }

    public boolean isUnlocked(UUID playerId, MagicSpell spell) {
        if (spell == null) {
            return false;
        }
        return this.schoolXp(playerId, spell.schoolKey()) >= spell.rank().xpRequired();
    }

    public int schoolXp(UUID playerId, String schoolKey) {
        return Math.max(0, this.get(playerId).schoolXp().getOrDefault(this.normalizeSchool(schoolKey), 0));
    }

    public AbilityRank masteryRank(UUID playerId, String schoolKey) {
        int xp = this.schoolXp(playerId, schoolKey);
        AbilityRank current = AbilityRank.NOVICE;
        for (AbilityRank rank : AbilityRank.values()) {
            if (xp >= rank.xpRequired()) {
                current = rank;
            }
        }
        return current;
    }

    public void addSchoolXp(Player player, String schoolKey, int amount, String reason) {
        if (player == null || amount <= 0) {
            return;
        }
        String normalized = this.normalizeSchool(schoolKey);
        MagicProfile profile = this.get(player.getUniqueId());
        AbilityRank oldRank = this.masteryRank(player.getUniqueId(), normalized);
        profile.schoolXp().put(normalized, Math.max(0, profile.schoolXp().getOrDefault(normalized, 0) + amount));
        AbilityRank newRank = this.masteryRank(player.getUniqueId(), normalized);
        String schoolName = SCHOOLS.getOrDefault(normalized, normalized);
        if (newRank.ordinal() > oldRank.ordinal()) {
            player.sendMessage(schoolName + " mastery advanced to " + newRank.displayName() + ".");
        } else if (reason != null && !reason.isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(schoolName + " XP +" + amount + " - " + reason, net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
        this.save(profile);
    }

    public void resetSchool(Player player, String schoolKey) {
        if (player == null) {
            return;
        }
        String normalized = this.normalizeSchool(schoolKey);
        MagicProfile profile = this.get(player.getUniqueId());
        profile.schoolXp().put(normalized, 0);
        this.save(profile);
    }

    public int xpForNextRank(MagicProfile profile) {
        if (profile.rank() >= 5) {
            return -1;
        }
        return RANK_XP[profile.rank() + 1];
    }

    public void addXp(Player player, int amount, String reason) {
        if (player == null || amount <= 0) {
            return;
        }
        MagicProfile profile = this.get(player.getUniqueId());
        profile.setXp(profile.xp() + amount);
        int oldRank = profile.rank();
        this.applyRankUps(profile);
        if (profile.rank() > oldRank) {
            player.sendMessage("Arcane Rank " + profile.rank() + " reached. New spellwork has awakened.");
        } else if (reason != null && !reason.isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("Arcane XP +" + amount + " - " + reason, net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
        this.save(profile);
    }

    public void recordPractice(Player player, String spellKey, String schoolKey, int hits, boolean support) {
        MagicProfile profile = this.get(player.getUniqueId());
        this.resetDailyIfNeeded(profile);
        profile.setDailyCasts(profile.dailyCasts() + 1);
        if (hits > 0) {
            profile.setDailyHits(profile.dailyHits() + hits);
        }
        if (support) {
            profile.setDailySupport(profile.dailySupport() + 1);
        }
        int xp = 5 + Math.min(10, Math.max(0, hits) * 2) + (support ? 2 : 0);
        this.addXp(player, xp, this.displayReason(spellKey));
        this.addSchoolXp(player, schoolKey, xp, this.displayReason(spellKey));
        this.checkDaily(player, profile, "casts", profile.dailyCasts(), 10, "Arcane Practice: cast 10 spells");
        this.checkDaily(player, profile, "hits", profile.dailyHits(), 5, "Arcane Practice: hit 5 hostile mobs");
        this.checkDaily(player, profile, "support", profile.dailySupport(), 3, "Arcane Practice: use 3 support spells");
        this.save(profile);
    }

    public void grantRank(Player player, int rank) {
        MagicProfile profile = this.get(player.getUniqueId());
        profile.setRank(rank);
        this.unlockForRank(profile);
        this.save(profile);
    }

    public void resetProgress(Player player) {
        MagicProfile profile = this.get(player.getUniqueId());
        profile.setRank(1);
        profile.setXp(0);
        profile.schoolXp().clear();
        profile.learnedSpells().clear();
        profile.learnedSpells().addAll(List.of(STARTER_SPELLS));
        profile.completedDaily().clear();
        profile.setDailyDate(LocalDate.now().toString());
        profile.setDailyCasts(0);
        profile.setDailyHits(0);
        profile.setDailySupport(0);
        this.save(profile);
    }

    private MagicProfile loadProfile(UUID playerId) {
        MagicProfile profile = new MagicProfile(playerId);
        String path = "players." + playerId;
        profile.setRank(this.data.getInt(path + ".rank", 1));
        profile.setXp(this.data.getInt(path + ".xp", 0));
        ConfigurationSection schoolSection = this.data.getConfigurationSection(path + ".school-xp");
        if (schoolSection != null) {
            for (String key : schoolSection.getKeys(false)) {
                profile.schoolXp().put(this.normalizeSchool(key), Math.max(0, schoolSection.getInt(key, 0)));
            }
        }
        profile.setDailyDate(this.data.getString(path + ".daily.date", ""));
        profile.setDailyCasts(this.data.getInt(path + ".daily.casts", 0));
        profile.setDailyHits(this.data.getInt(path + ".daily.hits", 0));
        profile.setDailySupport(this.data.getInt(path + ".daily.support", 0));
        profile.completedDaily().addAll(this.data.getStringList(path + ".daily.completed"));
        List<String> learned = this.data.getStringList(path + ".learned");
        if (learned.isEmpty()) {
            profile.learnedSpells().addAll(List.of(STARTER_SPELLS));
            profile.bindings().put("SNEAK_RIGHT_CLICK", "ember_bolt");
            profile.bindings().put("SNEAK_LEFT_CLICK", "starlight_flicker");
            profile.bindings().put("SNEAK_SWAP_HAND", "aether_step");
            profile.bindings().put("SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK", "arcane_ward");
            profile.bindings().put("RIGHT_CLICK", "verdant_mend");
            profile.bindings().put("LEFT_CLICK", "gravity_snare");
            this.unlockForRank(profile);
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
        this.unlockForRank(profile);
        return profile;
    }

    private void unlockForRank(MagicProfile profile) {
        for (Map.Entry<String, Integer> entry : UNLOCK_RANKS.entrySet()) {
            if (profile.rank() >= entry.getValue()) {
                profile.learnedSpells().add(entry.getKey());
            }
        }
    }

    private void resetDailyIfNeeded(MagicProfile profile) {
        String today = LocalDate.now().toString();
        if (!today.equals(profile.dailyDate())) {
            profile.setDailyDate(today);
            profile.setDailyCasts(0);
            profile.setDailyHits(0);
            profile.setDailySupport(0);
            profile.completedDaily().clear();
        }
    }

    private void checkDaily(Player player, MagicProfile profile, String key, int value, int target, String label) {
        if (value < target || profile.completedDaily().contains(key)) {
            return;
        }
        profile.completedDaily().add(key);
        player.sendMessage(label + " complete. +35 Arcane XP.");
        profile.setXp(profile.xp() + 35);
        int oldRank = profile.rank();
        this.applyRankUps(profile);
        if (profile.rank() > oldRank) {
            player.sendMessage("Arcane Rank " + profile.rank() + " reached. New spellwork has awakened.");
        }
    }

    private void applyRankUps(MagicProfile profile) {
        while (profile.rank() < 5 && profile.xp() >= RANK_XP[profile.rank() + 1]) {
            profile.setRank(profile.rank() + 1);
            this.unlockForRank(profile);
        }
    }

    private String displayReason(String spellKey) {
        return spellKey == null ? "practice" : spellKey.replace('_', ' ');
    }

    private String normalizeSchool(String schoolKey) {
        if (schoolKey == null || schoolKey.isBlank()) {
            return "astral";
        }
        return schoolKey.toLowerCase(Locale.ROOT);
    }
}
