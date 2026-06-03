package com.xkstudios.crowns.swords;

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

public class SwordProfileManager {
    public static final String[] STARTER_SKILLS = {
            "linear",
            "horizontal_arc",
            "starburst_step",
            "guard_breaker",
            "aegis_parry",
            "whirling_edge"
    };
    public static final Map<String, String> STYLES = Map.of(
            "flash", "Flash Style",
            "guard", "Guard Style",
            "phantom", "Phantom Style"
    );
    private static final int[] RANK_XP = {0, 0, 100, 250, 450, 700};
    private static final Map<String, Integer> UNLOCK_RANKS = new LinkedHashMap<>();

    static {
        for (String skill : STARTER_SKILLS) {
            UNLOCK_RANKS.put(skill, 1);
        }
        UNLOCK_RANKS.put("rising_cut", 2);
        UNLOCK_RANKS.put("crescent_lunge", 3);
        UNLOCK_RANKS.put("phantom_riposte", 4);
    }

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
        this.data.set(path + ".rank", profile.rank());
        this.data.set(path + ".xp", profile.xp());
        this.data.set(path + ".style-xp", null);
        for (Map.Entry<String, Integer> entry : profile.styleXp().entrySet()) {
            this.data.set(path + ".style-xp." + entry.getKey(), Math.max(0, entry.getValue()));
        }
        this.data.set(path + ".daily.date", profile.dailyDate());
        this.data.set(path + ".daily.arts", profile.dailyArts());
        this.data.set(path + ".daily.hits", profile.dailyHits());
        this.data.set(path + ".daily.guard", profile.dailyGuard());
        this.data.set(path + ".daily.completed", profile.completedDaily().stream().toList());
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

    public int requiredRank(String skillKey) {
        return UNLOCK_RANKS.getOrDefault(skillKey, 1);
    }

    public boolean isUnlocked(UUID playerId, String skillKey) {
        SwordSkill skill = this.plugin.skills().skill(skillKey);
        if (skill != null) {
            return this.isUnlocked(playerId, skill);
        }
        return this.get(playerId).rank() >= this.requiredRank(skillKey);
    }

    public boolean isUnlocked(UUID playerId, SwordSkill skill) {
        if (skill == null) {
            return false;
        }
        return this.styleXp(playerId, skill.styleKey()) >= skill.rank().xpRequired();
    }

    public int styleXp(UUID playerId, String styleKey) {
        return Math.max(0, this.get(playerId).styleXp().getOrDefault(this.normalizeStyle(styleKey), 0));
    }

    public AbilityRank masteryRank(UUID playerId, String styleKey) {
        int xp = this.styleXp(playerId, styleKey);
        AbilityRank current = AbilityRank.NOVICE;
        for (AbilityRank rank : AbilityRank.values()) {
            if (xp >= rank.xpRequired()) {
                current = rank;
            }
        }
        return current;
    }

    public void addStyleXp(Player player, String styleKey, int amount, String reason) {
        if (player == null || amount <= 0) {
            return;
        }
        String normalized = this.normalizeStyle(styleKey);
        SwordProfile profile = this.get(player.getUniqueId());
        AbilityRank oldRank = this.masteryRank(player.getUniqueId(), normalized);
        profile.styleXp().put(normalized, Math.max(0, profile.styleXp().getOrDefault(normalized, 0) + amount));
        AbilityRank newRank = this.masteryRank(player.getUniqueId(), normalized);
        String styleName = STYLES.getOrDefault(normalized, normalized);
        if (newRank.ordinal() > oldRank.ordinal()) {
            player.sendMessage(styleName + " mastery advanced to " + newRank.displayName() + ".");
        } else if (reason != null && !reason.isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(styleName + " XP +" + amount + " - " + reason, net.kyori.adventure.text.format.NamedTextColor.AQUA));
        }
        this.save(profile);
    }

    public void resetStyle(Player player, String styleKey) {
        if (player == null) {
            return;
        }
        String normalized = this.normalizeStyle(styleKey);
        SwordProfile profile = this.get(player.getUniqueId());
        profile.styleXp().put(normalized, 0);
        this.save(profile);
    }

    public int xpForNextRank(SwordProfile profile) {
        if (profile.rank() >= 5) {
            return -1;
        }
        return RANK_XP[profile.rank() + 1];
    }

    public void addXp(Player player, int amount, String reason) {
        if (player == null || amount <= 0) {
            return;
        }
        SwordProfile profile = this.get(player.getUniqueId());
        profile.setXp(profile.xp() + amount);
        int oldRank = profile.rank();
        this.applyRankUps(profile);
        if (profile.rank() > oldRank) {
            player.sendMessage("Blade Rank " + profile.rank() + " reached. New sword arts are available.");
        } else if (reason != null && !reason.isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("Blade XP +" + amount + " - " + reason, net.kyori.adventure.text.format.NamedTextColor.AQUA));
        }
        this.save(profile);
    }

    public void recordPractice(Player player, String skillKey, String styleKey, int hits, boolean guard) {
        SwordProfile profile = this.get(player.getUniqueId());
        this.resetDailyIfNeeded(profile);
        profile.setDailyArts(profile.dailyArts() + 1);
        if (hits > 0) {
            profile.setDailyHits(profile.dailyHits() + hits);
        }
        if (guard) {
            profile.setDailyGuard(profile.dailyGuard() + 1);
        }
        int xp = 5 + Math.min(10, Math.max(0, hits) * 2) + (guard ? 2 : 0);
        this.addXp(player, xp, this.displayReason(skillKey));
        this.addStyleXp(player, styleKey, xp, this.displayReason(skillKey));
        this.checkDaily(player, profile, "arts", profile.dailyArts(), 10, "Blade Practice: use 10 sword arts");
        this.checkDaily(player, profile, "hits", profile.dailyHits(), 10, "Blade Practice: land 10 sword-art hits");
        this.checkDaily(player, profile, "guard", profile.dailyGuard(), 3, "Blade Practice: use 3 guard/counter arts");
        this.save(profile);
    }

    public void grantRank(Player player, int rank) {
        SwordProfile profile = this.get(player.getUniqueId());
        profile.setRank(rank);
        this.unlockForRank(profile);
        this.save(profile);
    }

    public void resetProgress(Player player) {
        SwordProfile profile = this.get(player.getUniqueId());
        profile.setRank(1);
        profile.setXp(0);
        profile.styleXp().clear();
        profile.learnedSkills().clear();
        profile.learnedSkills().addAll(List.of(STARTER_SKILLS));
        profile.completedDaily().clear();
        profile.setDailyDate(LocalDate.now().toString());
        profile.setDailyArts(0);
        profile.setDailyHits(0);
        profile.setDailyGuard(0);
        this.save(profile);
    }

    private SwordProfile loadProfile(UUID playerId) {
        SwordProfile profile = new SwordProfile(playerId);
        String path = "players." + playerId;
        profile.setRank(this.data.getInt(path + ".rank", 1));
        profile.setXp(this.data.getInt(path + ".xp", 0));
        ConfigurationSection styleSection = this.data.getConfigurationSection(path + ".style-xp");
        if (styleSection != null) {
            for (String key : styleSection.getKeys(false)) {
                profile.styleXp().put(this.normalizeStyle(key), Math.max(0, styleSection.getInt(key, 0)));
            }
        }
        profile.setDailyDate(this.data.getString(path + ".daily.date", ""));
        profile.setDailyArts(this.data.getInt(path + ".daily.arts", 0));
        profile.setDailyHits(this.data.getInt(path + ".daily.hits", 0));
        profile.setDailyGuard(this.data.getInt(path + ".daily.guard", 0));
        profile.completedDaily().addAll(this.data.getStringList(path + ".daily.completed"));
        List<String> learned = this.data.getStringList(path + ".learned");
        if (learned.isEmpty()) {
            profile.learnedSkills().addAll(List.of(STARTER_SKILLS));
            profile.bindings().put("SNEAK_RIGHT_CLICK", "linear");
            profile.bindings().put("SNEAK_LEFT_CLICK", "horizontal_arc");
            profile.bindings().put("SNEAK_SWAP_HAND", "starburst_step");
            profile.bindings().put("SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK", "guard_breaker");
            profile.bindings().put("RIGHT_CLICK", "aegis_parry");
            profile.bindings().put("LEFT_CLICK", "whirling_edge");
            this.unlockForRank(profile);
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
        this.unlockForRank(profile);
        return profile;
    }

    private void unlockForRank(SwordProfile profile) {
        for (Map.Entry<String, Integer> entry : UNLOCK_RANKS.entrySet()) {
            if (profile.rank() >= entry.getValue()) {
                profile.learnedSkills().add(entry.getKey());
            }
        }
    }

    private void resetDailyIfNeeded(SwordProfile profile) {
        String today = LocalDate.now().toString();
        if (!today.equals(profile.dailyDate())) {
            profile.setDailyDate(today);
            profile.setDailyArts(0);
            profile.setDailyHits(0);
            profile.setDailyGuard(0);
            profile.completedDaily().clear();
        }
    }

    private void checkDaily(Player player, SwordProfile profile, String key, int value, int target, String label) {
        if (value < target || profile.completedDaily().contains(key)) {
            return;
        }
        profile.completedDaily().add(key);
        player.sendMessage(label + " complete. +35 Blade XP.");
        profile.setXp(profile.xp() + 35);
        int oldRank = profile.rank();
        this.applyRankUps(profile);
        if (profile.rank() > oldRank) {
            player.sendMessage("Blade Rank " + profile.rank() + " reached. New sword arts are available.");
        }
    }

    private void applyRankUps(SwordProfile profile) {
        while (profile.rank() < 5 && profile.xp() >= RANK_XP[profile.rank() + 1]) {
            profile.setRank(profile.rank() + 1);
            this.unlockForRank(profile);
        }
    }

    private String displayReason(String skillKey) {
        return skillKey == null ? "practice" : skillKey.replace('_', ' ');
    }

    private String normalizeStyle(String styleKey) {
        if (styleKey == null || styleKey.isBlank()) {
            return "flash";
        }
        return styleKey.toLowerCase(Locale.ROOT);
    }
}
