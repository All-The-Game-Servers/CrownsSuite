package com.xkstudios.crowns.mmo;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyProvider;
import com.xkstudios.crowns.api.MmoProvider;
import com.xkstudios.crowns.data.DataManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class MmoManager implements MmoProvider {
    private final CrownsPlugin plugin;
    private final DataManager dataManager;
    private final Map<UUID, EnumMap<MmoSkill, SkillState>> skillCache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> perkCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, WorldProgressEntry>> worldCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<MmoSkill, ActionRepeatState>> repeatTracker = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> recentXp = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Map<MmoSkill, List<MmoPerkNode>> perksBySkill = new EnumMap<>(MmoSkill.class);
    private final Map<String, ActiveSkill> activeSkills = new LinkedHashMap<>();

    public MmoManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.seedPerks();
        this.seedActives();
    }

    public void initialize() {
        this.ensureTables();
    }

    public Collection<MmoSkill> getSkills() {
        return List.of(MmoSkill.values());
    }

    public List<MmoSkill> getSkillsByFamily(String family) {
        return this.getSkills().stream()
                .filter(skill -> skill.family().equalsIgnoreCase(family))
                .toList();
    }

    public List<MmoPerkNode> getPerks(MmoSkill skill) {
        return this.perksBySkill.getOrDefault(skill, List.of());
    }

    public Collection<ActiveSkill> getActiveSkills() {
        return this.activeSkills.values();
    }

    public SkillState getState(UUID playerId, String playerName, MmoSkill skill) {
        this.ensureLoaded(playerId, playerName);
        return this.skillCache.get(playerId).get(skill);
    }

    public int getLevel(UUID playerId, String playerName, MmoSkill skill) {
        return this.getState(playerId, playerName, skill).level();
    }

    public long getXp(UUID playerId, String playerName, MmoSkill skill) {
        return this.getState(playerId, playerName, skill).xp();
    }

    public long getCurrentLevelXp(UUID playerId, String playerName, MmoSkill skill) {
        SkillState state = this.getState(playerId, playerName, skill);
        return state.xp() - this.totalXpForLevel(state.level());
    }

    public long getNeededLevelXp(UUID playerId, String playerName, MmoSkill skill) {
        SkillState state = this.getState(playerId, playerName, skill);
        return this.xpToNextLevel(state.level());
    }

    public double getProgressPercent(UUID playerId, String playerName, MmoSkill skill) {
        long needed = Math.max(1L, this.getNeededLevelXp(playerId, playerName, skill));
        return Math.min(1.0D, (double) this.getCurrentLevelXp(playerId, playerName, skill) / (double) needed);
    }

    public int getTotalLevel(UUID playerId, String playerName) {
        this.ensureLoaded(playerId, playerName);
        return this.skillCache.get(playerId).values().stream().mapToInt(SkillState::level).sum();
    }

    public int getUnlockedPerkCount(UUID playerId, String playerName, MmoSkill skill) {
        this.ensureLoaded(playerId, playerName);
        return (int) this.perkCache.get(playerId).stream()
                .filter(key -> key.startsWith(skill.key() + ":"))
                .count();
    }

    public int getAvailablePerkPoints(UUID playerId, String playerName, MmoSkill skill) {
        int earned = this.getLevel(playerId, playerName, skill) / 5;
        return Math.max(0, earned - this.getUnlockedPerkCount(playerId, playerName, skill));
    }

    public boolean hasPerk(UUID playerId, String perkKey) {
        this.ensureLoaded(playerId, "");
        return this.perkCache.getOrDefault(playerId, Set.of()).contains(perkKey);
    }

    public boolean unlockPerk(Player player, MmoSkill skill, String perkKey) {
        if (player == null || skill == null || perkKey == null) {
            return false;
        }
        MmoPerkNode node = this.getPerks(skill).stream()
                .filter(perk -> perk.key().equalsIgnoreCase(perkKey))
                .findFirst()
                .orElse(null);
        if (node == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        this.ensureLoaded(playerId, player.getName());
        if (this.hasPerk(playerId, node.key())) {
            player.sendMessage(Component.text("You already unlocked " + node.displayName() + ".", NamedTextColor.YELLOW));
            return false;
        }
        if (this.getLevel(playerId, player.getName(), skill) < node.requiredLevel()) {
            player.sendMessage(Component.text("You need " + skill.displayName() + " level " + node.requiredLevel() + " first.", NamedTextColor.RED));
            return false;
        }
        if (this.getAvailablePerkPoints(playerId, player.getName(), skill) <= 0) {
            player.sendMessage(Component.text("You do not have a free perk point in " + skill.displayName() + ".", NamedTextColor.RED));
            return false;
        }
        this.perkCache.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(node.key());
        this.savePerkUnlock(playerId, player.getName(), skill, node.key());
        player.sendMessage(Component.text("Unlocked " + node.displayName() + ".", NamedTextColor.GREEN));
        CrownsAPI.publishAlert("mmo", "Perk unlocked", player.getName() + " unlocked " + node.displayName() + ".", playerId, false);
        return true;
    }

    public void addXp(Player player, MmoSkill skill, long amount, String actionKey) {
        if (player == null || skill == null || amount <= 0L) {
            return;
        }
        UUID playerId = player.getUniqueId();
        this.ensureLoaded(playerId, player.getName());
        double multiplier = this.applyRepeatScaling(playerId, skill, actionKey);
        multiplier *= this.plugin.getConfig().getDouble("mmo.xp-multiplier", 1.0D);
        multiplier *= this.skillXpBonusMultiplier(playerId, skill);
        long awarded = Math.max(1L, Math.round(amount * multiplier));
        SkillState state = this.getState(playerId, player.getName(), skill);
        long totalXp = state.xp() + awarded;
        int oldLevel = state.level();
        int newLevel = this.levelForXp(totalXp);
        SkillState updated = new SkillState(newLevel, totalXp);
        this.skillCache.get(playerId).put(skill, updated);
        this.saveSkillState(playerId, player.getName(), skill, updated);
        this.recordXp(playerId, skill, awarded, amount, actionKey, oldLevel, newLevel);
        if (this.plugin.getConfig().getBoolean("mmo.xp-debug", false)) {
            player.sendMessage(Component.text("[MMO XP] +" + awarded + " " + skill.displayName() + " from " + actionKey + " (" + oldLevel + " -> " + newLevel + ")", NamedTextColor.DARK_AQUA));
        }
        if (newLevel > oldLevel) {
            player.sendMessage(Component.text(skill.displayName() + " reached level " + newLevel + ".", NamedTextColor.GOLD));
            if (newLevel % 5 == 0) {
                player.sendMessage(Component.text("A new perk point is ready in " + skill.displayName() + ".", NamedTextColor.AQUA));
            }
            if (newLevel % 10 == 0) {
                CrownsAPI.publishAlert("mmo", "Major skill milestone", player.getName() + " reached " + skill.displayName() + " level " + newLevel + ".", null, false);
            }
        }
    }

    public boolean activate(Player player, String activeKey) {
        if (player == null || activeKey == null) {
            return false;
        }
        ActiveSkill active = this.activeSkills.get(activeKey.toLowerCase(Locale.ROOT));
        if (active == null) {
            player.sendMessage(Component.text("That active skill does not exist.", NamedTextColor.RED));
            return false;
        }
        if (this.getLevel(player.getUniqueId(), player.getName(), active.skill()) < active.requiredLevel()) {
            player.sendMessage(Component.text("You need " + active.skill().displayName() + " level " + active.requiredLevel() + ".", NamedTextColor.RED));
            return false;
        }
        if (!this.hasPerk(player.getUniqueId(), active.requiredPerk())) {
            player.sendMessage(Component.text("Unlock the " + active.displayName() + " perk first.", NamedTextColor.RED));
            return false;
        }
        long now = System.currentTimeMillis();
        long readyAt = this.cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .getOrDefault(active.key(), 0L);
        if (readyAt > now) {
            long seconds = Math.max(1L, (readyAt - now) / 1000L);
            player.sendMessage(Component.text(active.displayName() + " is on cooldown for " + seconds + "s.", NamedTextColor.YELLOW));
            return false;
        }
        this.applyActive(player, active);
        this.cooldowns.get(player.getUniqueId()).put(active.key(), now + active.cooldownSeconds() * 1000L);
        player.sendMessage(Component.text("Activated " + active.displayName() + ".", NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    public long getRemainingCooldown(UUID playerId, String activeKey) {
        long readyAt = this.cooldowns.getOrDefault(playerId, Map.of()).getOrDefault(activeKey, 0L);
        return Math.max(0L, readyAt - System.currentTimeMillis());
    }

    public boolean maybeGrantExtraOre(Player player, Material material) {
        if (player == null || material == null || !this.hasPerk(player.getUniqueId(), "mining:deep_pockets")) {
            return false;
        }
        if (!material.name().contains("ORE") && material != Material.ANCIENT_DEBRIS) {
            return false;
        }
        return this.random.nextDouble() < 0.10D;
    }

    public boolean maybeGrantExtraLog(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "woodcutting:spare_logs") && this.random.nextDouble() < 0.12D;
    }

    public boolean maybeGrantExtraCrop(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "farming:green_thumb") && this.random.nextDouble() < 0.15D;
    }

    public boolean maybeGrantExtraFish(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "fishing:lucky_hook") && this.random.nextDouble() < 0.12D;
    }

    public boolean maybeRefundLapis(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "enchanting:lapis_saver") && this.random.nextDouble() < 0.20D;
    }

    public boolean maybeRefundBrewIngredient(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "brewing:careful_hands") && this.random.nextDouble() < 0.15D;
    }

    public double getMeleeDamageMultiplier(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "swordsmanship:edge_mastery") ? 1.08D : 1.0D;
    }

    public double getRangedDamageMultiplier(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "archery:deadeye") ? 1.10D : 1.0D;
    }

    public double getDamageReduction(Player player) {
        return player != null && this.hasPerk(player.getUniqueId(), "defense:iron_guard") ? 0.08D : 0.0D;
    }

    public void maybeRefundArrow(Player player) {
        if (player != null && this.hasPerk(player.getUniqueId(), "archery:scavenger_quiver") && this.random.nextDouble() < 0.20D) {
            player.getInventory().addItem(new ItemStack(Material.ARROW));
        }
    }

    public void maybeTradingBonus(Player player) {
        if (player == null || !this.hasPerk(player.getUniqueId(), "trading:golden_tongue")) {
            return;
        }
        EconomyProvider economy = CrownsAPI.getEconomy();
        if (economy != null) {
            economy.deposit(player.getUniqueId(), 15L);
        }
    }

    public void markBossKill(Player player, String key, String detail) {
        this.markWorldProgress(player, "boss:" + key, detail);
        this.addXp(player, MmoSkill.EXPLORATION, this.plugin.getConfig().getLong("mmo.skills.exploration.boss-xp", 50L), "boss:" + key);
    }

    public void discoverBiome(Player player, String biomeKey) {
        String normalized = biomeKey.toLowerCase(Locale.ROOT);
        long biomeXp = this.plugin.getConfig().getLong("mmo.skills.exploration.biome-xp", 0L);
        if (biomeXp <= 0L && !this.plugin.getConfig().getBoolean("mmo.exploration.track-biome-discoveries", false)) {
            return;
        }
        boolean firstDiscovery = this.markWorldProgress(player, "biome:" + normalized, biomeKey);
        if (firstDiscovery && biomeXp > 0L) {
            this.addXp(player, MmoSkill.EXPLORATION, biomeXp, "biome:" + normalized);
        }
    }

    public int countWorldProgress(UUID playerId, String prefix) {
        this.ensureLoaded(playerId, "");
        return (int) this.worldCache.getOrDefault(playerId, Map.of()).keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .count();
    }

    public boolean hasWorldProgress(UUID playerId, String key) {
        this.ensureLoaded(playerId, "");
        return this.worldCache.getOrDefault(playerId, Map.of()).containsKey(key);
    }

    public List<String> getRecentXp(UUID playerId) {
        return List.copyOf(this.recentXp.getOrDefault(playerId, new ConcurrentLinkedDeque<>()));
    }

    public String getChapterLabel(UUID playerId, String playerName) {
        int totalLevel = this.getTotalLevel(playerId, playerName);
        int bosses = this.countWorldProgress(playerId, "boss:");
        if (totalLevel >= 120 && bosses >= 4) {
            return "Floor 50 Vanguard";
        }
        if (totalLevel >= 90 && bosses >= 3) {
            return "Floor 25 Raider";
        }
        if (totalLevel >= 60 && bosses >= 2) {
            return "Floor 10 Pathfinder";
        }
        if (totalLevel >= 30) {
            return "Floor 5 Prospect";
        }
        return "Floor 1 Initiate";
    }

    public List<MmoSkill> topSkills(UUID playerId, String playerName, int limit) {
        this.ensureLoaded(playerId, playerName);
        return this.skillCache.get(playerId).entrySet().stream()
                .sorted(Map.Entry.<MmoSkill, SkillState>comparingByValue(Comparator.comparingInt(SkillState::level).thenComparingLong(SkillState::xp)).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public String getProfileSummary(UUID playerId, String playerName) {
        return this.getChapterLabel(playerId, playerName) + " • Total level " + this.getTotalLevel(playerId, playerName);
    }

    @Override
    public String getTopSkillSummary(UUID playerId) {
        List<MmoSkill> skills = this.topSkills(playerId, "", 2);
        if (skills.isEmpty()) {
            return "Top skills: not started";
        }
        List<String> parts = new ArrayList<>();
        for (MmoSkill skill : skills) {
            parts.add(skill.displayName() + " " + this.getLevel(playerId, "", skill));
        }
        return "Top skills: " + String.join(", ", parts);
    }

    @Override
    public String getWorldProgressSummary(UUID playerId) {
        int bosses = this.countWorldProgress(playerId, "boss:");
        int biomes = this.countWorldProgress(playerId, "biome:");
        return "Bosses " + bosses + " • Discoveries " + biomes;
    }

    @Override
    public String getSystemStatusSummary() {
        String terrain = CrownsAPI.getTerrain() == null
                ? "Terrain: missing"
                : "Terrain: " + CrownsAPI.getTerrain().getFloorReadinessSummary(1);
        String economy = CrownsAPI.getEconomy() == null
                ? "Economy: missing"
                : "Economy: " + CrownsAPI.getEconomy().getMarketActivitySummary();
        return this.getSkills().size() + " skills online | " + terrain + " | " + economy;
    }

    public String formatLevelLine(UUID playerId, String playerName, MmoSkill skill) {
        long current = this.getCurrentLevelXp(playerId, playerName, skill);
        long needed = this.getNeededLevelXp(playerId, playerName, skill);
        return "Lvl " + this.getLevel(playerId, playerName, skill) + " • " + current + "/" + needed + " XP";
    }

    public String formatPerkLine(UUID playerId, String playerName, MmoSkill skill) {
        return "Perk points: " + this.getAvailablePerkPoints(playerId, playerName, skill);
    }

    private void ensureLoaded(UUID playerId, String playerName) {
        this.skillCache.computeIfAbsent(playerId, ignored -> this.loadSkills(playerId, playerName));
        this.perkCache.computeIfAbsent(playerId, ignored -> this.loadPerks(playerId));
        this.worldCache.computeIfAbsent(playerId, ignored -> this.loadWorldProgress(playerId));
    }

    private EnumMap<MmoSkill, SkillState> loadSkills(UUID playerId, String playerName) {
        EnumMap<MmoSkill, SkillState> states = new EnumMap<>(MmoSkill.class);
        for (MmoSkill skill : MmoSkill.values()) {
            states.put(skill, new SkillState(1, 0L));
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT skill_key, level, xp FROM mmo_skill_progress WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    MmoSkill skill = MmoSkill.fromKey(result.getString("skill_key"));
                    if (skill != null) {
                        states.put(skill, new SkillState(Math.max(1, result.getInt("level")), Math.max(0L, result.getLong("xp"))));
                    }
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load skill progress: " + exception.getMessage());
        }
        return states;
    }

    private Set<String> loadPerks(UUID playerId) {
        Set<String> perks = ConcurrentHashMap.newKeySet();
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT perk_key FROM mmo_perk_unlocks WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    perks.add(result.getString("perk_key"));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load perk unlocks: " + exception.getMessage());
        }
        return perks;
    }

    private Map<String, WorldProgressEntry> loadWorldProgress(UUID playerId) {
        Map<String, WorldProgressEntry> progress = new LinkedHashMap<>();
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "SELECT progress_key, detail, unlocked_at FROM mmo_world_progress WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    progress.put(result.getString("progress_key"),
                            new WorldProgressEntry(result.getString("progress_key"), result.getString("detail"), result.getLong("unlocked_at")));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not load world progress: " + exception.getMessage());
        }
        return progress;
    }

    private void saveSkillState(UUID playerId, String playerName, MmoSkill skill, SkillState state) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_skill_progress (player_uuid, player_name, skill_key, level, xp)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, skill.key());
            statement.setInt(4, state.level());
            statement.setLong(5, state.xp());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save skill state: " + exception.getMessage());
        }
    }

    private void savePerkUnlock(UUID playerId, String playerName, MmoSkill skill, String perkKey) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_perk_unlocks (player_uuid, player_name, skill_key, perk_key, unlocked_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, skill.key());
            statement.setString(4, perkKey);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save perk unlock: " + exception.getMessage());
        }
    }

    public boolean markWorldProgress(Player player, String key, String detail) {
        if (player == null || key == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        this.ensureLoaded(playerId, player.getName());
        if (this.worldCache.get(playerId).containsKey(key)) {
            return false;
        }
        WorldProgressEntry entry = new WorldProgressEntry(key, detail, System.currentTimeMillis());
        this.worldCache.get(playerId).put(key, entry);
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_world_progress (player_uuid, player_name, progress_key, detail, unlocked_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, player.getName());
            statement.setString(3, key);
            statement.setString(4, detail);
            statement.setLong(5, entry.unlockedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save world progress: " + exception.getMessage());
        }
        player.sendMessage(Component.text("World progress unlocked: " + detail, NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    private void recordXp(UUID playerId, MmoSkill skill, long awarded, long base, String actionKey, int oldLevel, int newLevel) {
        Deque<String> entries = this.recentXp.computeIfAbsent(playerId, ignored -> new ConcurrentLinkedDeque<>());
        entries.addFirst(skill.displayName() + " +" + awarded + " xp (base " + base + ") from " + actionKey + " | level " + oldLevel + " -> " + newLevel);
        while (entries.size() > 20) {
            entries.pollLast();
        }
    }

    private double applyRepeatScaling(UUID playerId, MmoSkill skill, String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return 1.0D;
        }
        long now = System.currentTimeMillis();
        long windowMs = this.plugin.getConfig().getLong("mmo.repeat-window-seconds", 45L) * 1000L;
        double floor = this.plugin.getConfig().getDouble("mmo.min-repeat-multiplier", 0.25D);
        ActionRepeatState state = this.repeatTracker
                .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(skill, ignored -> new ActionRepeatState("", 0L, 0));
        if (actionKey.equals(state.key) && now - state.lastAt <= windowMs) {
            state.streak++;
        } else {
            state.key = actionKey;
            state.streak = 0;
        }
        state.lastAt = now;
        return Math.max(floor, 1.0D - (state.streak * 0.15D));
    }

    private double skillXpBonusMultiplier(UUID playerId, MmoSkill skill) {
        return switch (skill) {
            case MINING -> this.hasPerk(playerId, "mining:tunnel_vision") ? 1.25D : 1.0D;
            case WOODCUTTING -> this.hasPerk(playerId, "woodcutting:clean_chops") ? 1.25D : 1.0D;
            case FARMING -> this.hasPerk(playerId, "farming:ranch_hand") ? 1.25D : 1.0D;
            case FISHING -> this.hasPerk(playerId, "fishing:patient_angler") ? 1.25D : 1.0D;
            case SMITHING -> this.hasPerk(playerId, "smithing:forge_memory") ? 1.25D : 1.0D;
            case ENCHANTING -> this.hasPerk(playerId, "enchanting:scholar_focus") ? 1.25D : 1.0D;
            case BREWING -> this.hasPerk(playerId, "brewing:practiced_brewer") ? 1.25D : 1.0D;
            case TRADING -> this.hasPerk(playerId, "trading:caravaner") ? 1.25D : 1.0D;
            case DEFENSE -> this.hasPerk(playerId, "defense:unbroken") ? 1.20D : 1.0D;
            case EXPLORATION -> this.hasPerk(playerId, "exploration:trailblazer") ? 1.25D : 1.0D;
            case SWORDSMANSHIP -> this.hasPerk(playerId, "swordsmanship:boss_hunter") ? 1.15D : 1.0D;
            case ARCHERY -> this.hasPerk(playerId, "archery:scavenger_quiver") ? 1.10D : 1.0D;
        };
    }

    private long xpToNextLevel(int level) {
        long base = this.plugin.getConfig().getLong("mmo.level-curve.base", 100L);
        long growth = this.plugin.getConfig().getLong("mmo.level-curve.growth", 35L);
        return base + Math.max(0, level - 1) * growth;
    }

    private long totalXpForLevel(int level) {
        long total = 0L;
        for (int current = 1; current < Math.max(1, level); current++) {
            total += this.xpToNextLevel(current);
        }
        return total;
    }

    private int levelForXp(long xp) {
        int level = 1;
        long remaining = Math.max(0L, xp);
        while (remaining >= this.xpToNextLevel(level)) {
            remaining -= this.xpToNextLevel(level);
            level++;
            if (level >= 1000) {
                break;
            }
        }
        return level;
    }

    private void applyActive(Player player, ActiveSkill active) {
        switch (active.key()) {
            case "battle-surge" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, active.durationSeconds() * 20, 0, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, active.durationSeconds() * 20, 0, true, true, true));
            }
            case "ranger-focus" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, active.durationSeconds() * 20, 0, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, active.durationSeconds() * 20, 0, true, true, true));
            }
            case "bulwark" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, active.durationSeconds() * 20, 0, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, active.durationSeconds() * 20, 1, true, true, true));
            }
            case "pathfinder" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, active.durationSeconds() * 20, 1, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, active.durationSeconds() * 20, 0, true, true, true));
            }
            default -> {
            }
        }
    }

    public record SkillState(int level, long xp) {
    }

    public record WorldProgressEntry(String key, String detail, long unlockedAt) {
    }

    public record ActiveSkill(
            String key,
            String displayName,
            MmoSkill skill,
            int requiredLevel,
            String requiredPerk,
            String description,
            int cooldownSeconds,
            int durationSeconds
    ) {
    }

    private static final class ActionRepeatState {
        private String key;
        private long lastAt;
        private int streak;

        private ActionRepeatState(String key, long lastAt, int streak) {
            this.key = key;
            this.lastAt = lastAt;
            this.streak = streak;
        }
    }

    private void ensureTables() {
        try (Statement statement = this.dataManager.getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_skill_progress (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        skill_key TEXT NOT NULL,
                        level INTEGER NOT NULL DEFAULT 1,
                        xp INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, skill_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_perk_unlocks (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        skill_key TEXT NOT NULL,
                        perk_key TEXT NOT NULL,
                        unlocked_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, perk_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_world_progress (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        progress_key TEXT NOT NULL,
                        detail TEXT,
                        unlocked_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, progress_key)
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Table setup failed: " + exception.getMessage());
        }
    }

    private void seedPerks() {
        this.perksBySkill.put(MmoSkill.MINING, List.of(
                new MmoPerkNode("mining:deep_pockets", MmoSkill.MINING, "Deep Pockets", 5, "Chance to pull an extra ore drop."),
                new MmoPerkNode("mining:tunnel_vision", MmoSkill.MINING, "Tunnel Vision", 15, "Bonus mining XP from deep work."),
                new MmoPerkNode("mining:vein_hunter", MmoSkill.MINING, "Vein Hunter", 25, "Marks you as a true ore diver.")
        ));
        this.perksBySkill.put(MmoSkill.WOODCUTTING, List.of(
                new MmoPerkNode("woodcutting:spare_logs", MmoSkill.WOODCUTTING, "Spare Logs", 5, "Chance to cut an extra log free."),
                new MmoPerkNode("woodcutting:clean_chops", MmoSkill.WOODCUTTING, "Clean Chops", 15, "Bonus woodcutting XP from tree work."),
                new MmoPerkNode("woodcutting:forester_stride", MmoSkill.WOODCUTTING, "Forester Stride", 25, "Improves your deep-forest identity.")
        ));
        this.perksBySkill.put(MmoSkill.FARMING, List.of(
                new MmoPerkNode("farming:green_thumb", MmoSkill.FARMING, "Green Thumb", 5, "Chance to harvest bonus crops."),
                new MmoPerkNode("farming:ranch_hand", MmoSkill.FARMING, "Ranch Hand", 15, "Bonus farming XP from harvest loops."),
                new MmoPerkNode("farming:harvest_blessing", MmoSkill.FARMING, "Harvest Blessing", 25, "Signals a refined agricultural build.")
        ));
        this.perksBySkill.put(MmoSkill.FISHING, List.of(
                new MmoPerkNode("fishing:lucky_hook", MmoSkill.FISHING, "Lucky Hook", 5, "Chance to pull a second catch."),
                new MmoPerkNode("fishing:patient_angler", MmoSkill.FISHING, "Patient Angler", 15, "Bonus fishing XP from steady catches."),
                new MmoPerkNode("fishing:tide_reader", MmoSkill.FISHING, "Tide Reader", 25, "Improves your waterside mastery profile.")
        ));
        this.perksBySkill.put(MmoSkill.SMITHING, List.of(
                new MmoPerkNode("smithing:forge_memory", MmoSkill.SMITHING, "Forge Memory", 5, "Bonus smithing XP from crafted upgrades."),
                new MmoPerkNode("smithing:tempered_hands", MmoSkill.SMITHING, "Tempered Hands", 15, "Makes repeated smithing less punishing."),
                new MmoPerkNode("smithing:artisan_mark", MmoSkill.SMITHING, "Artisan Mark", 25, "A prestige unlock for master smiths.")
        ));
        this.perksBySkill.put(MmoSkill.ENCHANTING, List.of(
                new MmoPerkNode("enchanting:lapis_saver", MmoSkill.ENCHANTING, "Lapis Saver", 5, "Chance to refund one lapis on enchant."),
                new MmoPerkNode("enchanting:scholar_focus", MmoSkill.ENCHANTING, "Scholar Focus", 15, "Bonus enchanting XP from table use."),
                new MmoPerkNode("enchanting:arcane_reserve", MmoSkill.ENCHANTING, "Arcane Reserve", 25, "Marks true arcane specialization.")
        ));
        this.perksBySkill.put(MmoSkill.BREWING, List.of(
                new MmoPerkNode("brewing:careful_hands", MmoSkill.BREWING, "Careful Hands", 5, "Chance to preserve brewing ingredients."),
                new MmoPerkNode("brewing:practiced_brewer", MmoSkill.BREWING, "Practiced Brewer", 15, "Bonus brewing XP from potion work."),
                new MmoPerkNode("brewing:catalyst_memory", MmoSkill.BREWING, "Catalyst Memory", 25, "Improves your alchemist profile.")
        ));
        this.perksBySkill.put(MmoSkill.TRADING, List.of(
                new MmoPerkNode("trading:golden_tongue", MmoSkill.TRADING, "Golden Tongue", 5, "Gain a small Crowns bonus on trades."),
                new MmoPerkNode("trading:caravaner", MmoSkill.TRADING, "Caravaner", 15, "Bonus trading XP from merchant routes."),
                new MmoPerkNode("trading:house_favorite", MmoSkill.TRADING, "House Favorite", 25, "Signals a dominant merchant build.")
        ));
        this.perksBySkill.put(MmoSkill.SWORDSMANSHIP, List.of(
                new MmoPerkNode("swordsmanship:edge_mastery", MmoSkill.SWORDSMANSHIP, "Edge Mastery", 5, "Boost melee damage slightly."),
                new MmoPerkNode("swordsmanship:battle_surge", MmoSkill.SWORDSMANSHIP, "Battle Surge", 10, "Unlock the Battle Surge active skill."),
                new MmoPerkNode("swordsmanship:boss_hunter", MmoSkill.SWORDSMANSHIP, "Boss Hunter", 20, "Bonus combat XP against major targets.")
        ));
        this.perksBySkill.put(MmoSkill.ARCHERY, List.of(
                new MmoPerkNode("archery:deadeye", MmoSkill.ARCHERY, "Deadeye", 5, "Boost ranged damage slightly."),
                new MmoPerkNode("archery:ranger_focus", MmoSkill.ARCHERY, "Ranger Focus", 10, "Unlock the Ranger Focus active skill."),
                new MmoPerkNode("archery:scavenger_quiver", MmoSkill.ARCHERY, "Scavenger Quiver", 20, "Chance to recover arrows and gain bonus XP.")
        ));
        this.perksBySkill.put(MmoSkill.DEFENSE, List.of(
                new MmoPerkNode("defense:iron_guard", MmoSkill.DEFENSE, "Iron Guard", 5, "Reduce incoming damage slightly."),
                new MmoPerkNode("defense:bulwark", MmoSkill.DEFENSE, "Bulwark", 10, "Unlock the Bulwark active skill."),
                new MmoPerkNode("defense:unbroken", MmoSkill.DEFENSE, "Unbroken", 20, "Gain bonus defense XP from surviving pressure.")
        ));
        this.perksBySkill.put(MmoSkill.EXPLORATION, List.of(
                new MmoPerkNode("exploration:trailblazer", MmoSkill.EXPLORATION, "Trailblazer", 5, "You are learning to read roads, camps, and safe routes."),
                new MmoPerkNode("exploration:pathfinder", MmoSkill.EXPLORATION, "Pathfinder", 15, "Unlock the Pathfinder active skill for serious expeditions."),
                new MmoPerkNode("exploration:landmark_memory", MmoSkill.EXPLORATION, "Landmark Memory", 40, "Marks a true front-line explorer who remembers the shape of the floors.")
        ));
    }

    private void seedActives() {
        this.activeSkills.put("battle-surge", new ActiveSkill("battle-surge", "Battle Surge", MmoSkill.SWORDSMANSHIP, 10, "swordsmanship:battle_surge", "Strength and speed for a short duel spike.", this.plugin.getConfig().getInt("mmo.active-skills.battle-surge.cooldown-seconds", 120), this.plugin.getConfig().getInt("mmo.active-skills.battle-surge.duration-seconds", 10)));
        this.activeSkills.put("ranger-focus", new ActiveSkill("ranger-focus", "Ranger Focus", MmoSkill.ARCHERY, 10, "archery:ranger_focus", "Movement and vision support for ranged fights.", this.plugin.getConfig().getInt("mmo.active-skills.ranger-focus.cooldown-seconds", 90), this.plugin.getConfig().getInt("mmo.active-skills.ranger-focus.duration-seconds", 12)));
        this.activeSkills.put("bulwark", new ActiveSkill("bulwark", "Bulwark", MmoSkill.DEFENSE, 10, "defense:bulwark", "Resistance spike for frontline survival.", this.plugin.getConfig().getInt("mmo.active-skills.bulwark.cooldown-seconds", 150), this.plugin.getConfig().getInt("mmo.active-skills.bulwark.duration-seconds", 8)));
        this.activeSkills.put("pathfinder", new ActiveSkill("pathfinder", "Pathfinder", MmoSkill.EXPLORATION, 15, "exploration:pathfinder", "Travel burst for expeditions and discovery runs.", this.plugin.getConfig().getInt("mmo.active-skills.pathfinder.cooldown-seconds", 120), this.plugin.getConfig().getInt("mmo.active-skills.pathfinder.duration-seconds", 15)));
    }
}
