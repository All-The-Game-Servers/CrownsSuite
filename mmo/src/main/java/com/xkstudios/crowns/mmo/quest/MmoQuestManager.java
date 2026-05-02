package com.xkstudios.crowns.mmo.quest;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyProvider;
import com.xkstudios.crowns.api.TerrainPoint;
import com.xkstudios.crowns.api.TerrainProvider;
import com.xkstudios.crowns.data.DataManager;
import com.xkstudios.crowns.mmo.MmoSkill;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class MmoQuestManager {
    private final CrownsPlugin plugin;
    private final DataManager dataManager;
    private final Map<String, QuestDefinition> quests = new LinkedHashMap<>();
    private final Map<UUID, Map<String, QuestProgress>> progressCache = new ConcurrentHashMap<>();

    public MmoQuestManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
    }

    public void initialize() {
        this.ensureTables();
        this.reload();
    }

    public void reload() {
        this.quests.clear();
        this.loadDefaultQuests();
        this.loadConfiguredQuests();
        this.progressCache.clear();
    }

    public Collection<QuestDefinition> getQuests() {
        return this.quests.values().stream()
                .sorted(Comparator.comparingInt(QuestDefinition::floor).thenComparing(QuestDefinition::key))
                .toList();
    }

    public QuestDefinition getQuest(String key) {
        return key == null ? null : this.quests.get(key.toLowerCase(Locale.ROOT));
    }

    public QuestProgress getProgress(UUID playerId, String playerName, String questKey) {
        this.ensureLoaded(playerId, playerName);
        QuestDefinition quest = this.getQuest(questKey);
        QuestProgress progress = this.progressCache.getOrDefault(playerId, Map.of()).get(questKey.toLowerCase(Locale.ROOT));
        if (progress != null) {
            return progress;
        }
        return new QuestProgress(questKey.toLowerCase(Locale.ROOT), "active", 0, 0L);
    }

    public List<QuestView> getViews(Player player) {
        List<QuestView> views = new ArrayList<>();
        this.ensureLoaded(player.getUniqueId(), player.getName());
        for (QuestDefinition quest : this.getQuests()) {
            views.add(new QuestView(quest, this.getProgress(player.getUniqueId(), player.getName(), quest.key())));
        }
        return views;
    }

    public List<QuestView> getActiveViews(Player player) {
        return this.getViews(player).stream()
                .filter(view -> !view.progress().isCompleted() && view.progress().progress() > 0)
                .toList();
    }

    public List<QuestView> getAvailableViews(Player player) {
        return this.getViews(player).stream()
                .filter(view -> !view.progress().isCompleted() && view.progress().progress() <= 0)
                .toList();
    }

    public List<QuestView> getCompletedViews(Player player) {
        return this.getViews(player).stream()
                .filter(view -> view.progress().isCompleted())
                .toList();
    }

    public List<QuestView> getViewsForFloor(Player player, int floor) {
        return this.getViews(player).stream()
                .filter(view -> view.quest().floor() == floor)
                .toList();
    }

    public List<QuestView> getStoryPathViews(Player player) {
        return this.getViews(player).stream()
                .filter(view -> "first_haven_path".equals(view.quest().questLine()))
                .toList();
    }

    public boolean grant(Player player, String questKey) {
        QuestDefinition quest = this.getQuest(questKey);
        if (player == null || quest == null) {
            return false;
        }
        QuestProgress progress = this.getProgress(player.getUniqueId(), player.getName(), quest.key());
        if (progress.isCompleted()) {
            return false;
        }
        this.complete(player, quest, quest.amount());
        return true;
    }

    public boolean reset(Player player, String questKey) {
        QuestDefinition quest = this.getQuest(questKey);
        if (player == null || quest == null) {
            return false;
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "DELETE FROM mmo_quest_progress WHERE player_uuid = ? AND quest_key = ?")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, quest.key());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not reset quest progress: " + exception.getMessage());
        }
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                "DELETE FROM mmo_quest_discoveries WHERE player_uuid = ? AND quest_key = ?")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, quest.key());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not reset quest discoveries: " + exception.getMessage());
        }
        this.progressCache.remove(player.getUniqueId());
        return true;
    }

    public void describe(Player player, String questKey) {
        QuestDefinition quest = this.getQuest(questKey);
        if (quest == null) {
            player.sendMessage(Component.text("Unknown quest: " + questKey, NamedTextColor.RED));
            return;
        }
        QuestProgress progress = this.getProgress(player.getUniqueId(), player.getName(), quest.key());
        player.sendMessage(Component.text("Quest: " + quest.title(), NamedTextColor.GOLD));
        player.sendMessage(Component.text(quest.description(), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Objective: " + quest.objectiveLine(), NamedTextColor.AQUA));
        player.sendMessage(Component.text("Hint: " + this.destinationHint(player, quest), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Progress: " + Math.min(progress.progress(), quest.amount()) + "/" + quest.amount()
                + (progress.isCompleted() ? " completed" : ""), progress.isCompleted() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        if ("turnin".equals(quest.objectiveType())) {
            player.sendMessage(Component.text("Run this command while holding/having the required items to turn them in.", NamedTextColor.GRAY));
            this.tryTurnIn(player, quest);
        }
    }

    public void increment(Player player, String objectiveType, String subject, int amount) {
        if (player == null || objectiveType == null || amount <= 0) {
            return;
        }
        String normalizedType = objectiveType.toLowerCase(Locale.ROOT);
        String normalizedSubject = subject == null ? "*" : subject.toLowerCase(Locale.ROOT);
        int floor = this.plugin.getFloorManager().getFloorNumber(player.getWorld());
        for (QuestDefinition quest : this.getQuests()) {
            if (!quest.objectiveType().equals(normalizedType) || quest.isFloorSpecificMismatch(floor)) {
                continue;
            }
            if (!quest.subjectMatches(normalizedSubject)) {
                continue;
            }
            this.addProgress(player, quest, amount);
        }
    }

    public void handleExplore(Player player) {
        TerrainProvider terrain = CrownsAPI.getTerrain();
        if (terrain == null) {
            this.handleFallbackExplore(player);
            return;
        }
        int floor = this.plugin.getFloorManager().getFloorNumber(player.getWorld());
        if (floor <= 0) {
            return;
        }
        for (QuestDefinition quest : this.getQuests()) {
            if (!"explore".equals(quest.objectiveType()) || quest.isFloorSpecificMismatch(floor)) {
                continue;
            }
            List<TerrainPoint> points = "*".equals(quest.subject())
                    ? terrain.getAllPoints(floor, player.getWorld().getName())
                    : terrain.getPoints(floor, player.getWorld().getName(), quest.subject());
            for (TerrainPoint point : points) {
                Location location = point.toLocation();
                if (location == null || !player.getWorld().equals(location.getWorld())) {
                    continue;
                }
                double radius = this.plugin.getConfig().getDouble("mmo.quests.explore-radius", 28.0D);
                if (player.getLocation().distance(location) <= radius && this.markDiscovered(player, quest.key(), point.key())) {
                    this.addProgress(player, quest, 1);
                    player.sendMessage(Component.text("Quest location discovered: " + point.displayName(), NamedTextColor.LIGHT_PURPLE));
                }
            }
        }
    }

    public boolean tryTurnIn(Player player, QuestDefinition quest) {
        if (quest == null || !"turnin".equals(quest.objectiveType())) {
            return false;
        }
        QuestProgress progress = this.getProgress(player.getUniqueId(), player.getName(), quest.key());
        if (progress.isCompleted()) {
            return false;
        }
        if (!this.hasTurnInItems(player, quest.subject(), quest.amount())) {
            return false;
        }
        this.removeTurnInItems(player, quest.subject(), quest.amount());
        this.complete(player, quest, quest.amount());
        return true;
    }

    private void addProgress(Player player, QuestDefinition quest, int amount) {
        QuestProgress current = this.getProgress(player.getUniqueId(), player.getName(), quest.key());
        if (current.isCompleted()) {
            return;
        }
        int next = Math.min(quest.amount(), current.progress() + amount);
        if (next >= quest.amount()) {
            this.complete(player, quest, next);
            return;
        }
        this.saveProgress(player, quest.key(), new QuestProgress(quest.key(), "active", next, 0L));
        player.sendMessage(Component.text("Quest progress: " + quest.title() + " " + next + "/" + quest.amount(), NamedTextColor.AQUA));
    }

    private void complete(Player player, QuestDefinition quest, int progress) {
        QuestProgress completed = new QuestProgress(quest.key(), "completed", Math.max(progress, quest.amount()), System.currentTimeMillis());
        this.saveProgress(player, quest.key(), completed);
        if (quest.rewardCurrency() > 0L) {
            EconomyProvider economy = CrownsAPI.getEconomy();
            if (economy != null) {
                economy.deposit(player.getUniqueId(), quest.rewardCurrency());
            } else {
                player.sendMessage(Component.text("Crowns reward skipped because CrownsEconomy is not installed.", NamedTextColor.YELLOW));
            }
        }
        if (quest.rewardXp() > 0L) {
            MmoSkill skill = MmoSkill.fromKey(quest.rewardSkill());
            this.plugin.getMmoManager().addXp(player, skill == null ? MmoSkill.EXPLORATION : skill, quest.rewardXp(), "quest:" + quest.key());
        }
        player.sendMessage(Component.text("Quest complete: " + quest.title(), NamedTextColor.GOLD));
        player.sendMessage(Component.text("Rewards: " + this.rewardLine(quest), NamedTextColor.YELLOW));
        CrownsAPI.publishAlert("mmo", "Quest complete", player.getName() + " completed " + quest.title() + ".", player.getUniqueId(), false);
    }

    private void saveProgress(Player player, String questKey, QuestProgress progress) {
        this.progressCache.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>()).put(questKey, progress);
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR REPLACE INTO mmo_quest_progress
                (player_uuid, player_name, quest_key, status, progress, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setString(3, questKey);
            statement.setString(4, progress.status());
            statement.setInt(5, progress.progress());
            statement.setLong(6, progress.completedAt());
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save quest progress: " + exception.getMessage());
        }
    }

    private boolean markDiscovered(Player player, String questKey, String pointKey) {
        try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement("""
                INSERT OR IGNORE INTO mmo_quest_discoveries
                (player_uuid, quest_key, point_key, discovered_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, questKey);
            statement.setString(3, pointKey);
            statement.setLong(4, System.currentTimeMillis());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Could not save quest discovery: " + exception.getMessage());
            return false;
        }
    }

    private void ensureLoaded(UUID playerId, String playerName) {
        this.progressCache.computeIfAbsent(playerId, ignored -> {
            Map<String, QuestProgress> loaded = new ConcurrentHashMap<>();
            try (PreparedStatement statement = this.dataManager.getConnection().prepareStatement(
                    "SELECT quest_key, status, progress, completed_at FROM mmo_quest_progress WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        loaded.put(result.getString("quest_key"), new QuestProgress(
                                result.getString("quest_key"),
                                result.getString("status"),
                                result.getInt("progress"),
                                result.getLong("completed_at")
                        ));
                    }
                }
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[CrownsMMO] Could not load quest progress: " + exception.getMessage());
            }
            return loaded;
        });
    }

    private boolean hasTurnInItems(Player player, String subject, int amount) {
        return this.countTurnInItems(player, subject) >= amount;
    }

    private int countTurnInItems(Player player, String subject) {
        int count = 0;
        Material material = Material.matchMaterial(subject.toUpperCase(Locale.ROOT));
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            String itemKey = this.plugin.getItemFactory().getItemKey(item);
            if (itemKey != null && itemKey.equalsIgnoreCase(subject)) {
                count += item.getAmount();
            } else if (material != null && item.getType() == material && itemKey == null) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeTurnInItems(Player player, String subject, int amount) {
        int remaining = amount;
        Material material = Material.matchMaterial(subject.toUpperCase(Locale.ROOT));
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR || remaining <= 0) {
                continue;
            }
            String itemKey = this.plugin.getItemFactory().getItemKey(item);
            boolean match = itemKey != null && itemKey.equalsIgnoreCase(subject)
                    || material != null && item.getType() == material && itemKey == null;
            if (!match) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            remaining -= removed;
        }
    }

    private void ensureTables() {
        try (Statement statement = this.dataManager.getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_quest_progress (
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        quest_key TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active',
                        progress INTEGER NOT NULL DEFAULT 0,
                        completed_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, quest_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mmo_quest_discoveries (
                        player_uuid TEXT NOT NULL,
                        quest_key TEXT NOT NULL,
                        point_key TEXT NOT NULL,
                        discovered_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, quest_key, point_key)
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[CrownsMMO] Quest table setup failed: " + exception.getMessage());
        }
    }

    public void sendInspect(CommandSender sender, Player target) {
        if (sender == null || target == null) {
            return;
        }
        List<QuestView> views = this.getViews(target);
        long completed = views.stream().filter(view -> view.progress().isCompleted()).count();
        long active = views.stream().filter(view -> !view.progress().isCompleted() && view.progress().progress() > 0).count();
        sender.sendMessage("CrownsMMO Quest Inspect: " + target.getName());
        sender.sendMessage("Total: " + views.size() + " | Active: " + active + " | Completed: " + completed);
        for (QuestView view : views.stream().filter(view -> view.progress().progress() > 0 || view.progress().isCompleted()).limit(12).toList()) {
            sender.sendMessage("- " + view.quest().key() + ": " + view.progress().status() + " "
                    + Math.min(view.progress().progress(), view.quest().amount()) + "/" + view.quest().amount());
        }
    }

    public String destinationHint(Player player, QuestDefinition quest) {
        if (quest == null) {
            return "No quest selected.";
        }
        if (!"explore".equals(quest.objectiveType())) {
            return quest.hint();
        }
        TerrainProvider terrain = CrownsAPI.getTerrain();
        if (terrain == null) {
            return "CrownsTerrain is not installed, so exploration uses floor travel milestones. " + quest.hint();
        }
        String worldName = this.floorWorldName(player, quest.floor());
        List<TerrainPoint> points = "*".equals(quest.subject())
                ? terrain.getAllPoints(quest.floor(), worldName)
                : terrain.getPoints(quest.floor(), worldName, quest.subject());
        if (points.isEmpty()) {
            return quest.hint();
        }
        TerrainPoint point = points.get(0);
        return point.displayName() + " near X " + point.x() + ", Z " + point.z();
    }

    public String providerLine(QuestDefinition quest) {
        if (quest == null) {
            return "Provider status unavailable.";
        }
        if ("explore".equals(quest.objectiveType())) {
            return CrownsAPI.getTerrain() == null ? "Terrain provider: optional, currently missing" : "Terrain provider: online";
        }
        if (quest.rewardCurrency() > 0L) {
            return CrownsAPI.getEconomy() == null ? "CrownsEconomy: reward will skip Crowns payout" : "CrownsEconomy: reward payout online";
        }
        return "No optional provider required.";
    }

    public String rewardLine(QuestDefinition quest) {
        String currency = quest.rewardCurrency() <= 0L
                ? "no Crowns"
                : CrownsAPI.getEconomy() == null ? quest.rewardCurrency() + " Crowns" : CrownsAPI.getEconomy().formatCurrency(quest.rewardCurrency());
        return currency + ", " + quest.rewardXp() + " " + quest.rewardSkill() + " XP";
    }

    private void loadDefaultQuests() {
        this.addQuest(new QuestDefinition("fhp-01-first-haven", "First Haven Path I: First Haven Scout", "Find a Floor 1 settlement and learn where the safe roads begin.", "explore", "village", 1, 1, 250L, 40L, "exploration", "first_haven_path", "Look for the closest generated village or haven settlement."));
        this.addQuest(new QuestDefinition("fhp-02-road-markers", "First Haven Path II: Road Marker Run", "Trace three Floor 1 road markers so you can navigate without being carried by coordinates.", "explore", "road_marker", 1, 3, 350L, 55L, "exploration", "first_haven_path", "Follow roads and marker stones outside the haven."));
        this.addQuest(new QuestDefinition("fhp-03-starter-stores", "First Haven Path III: Starter Stores", "Collect five Copperleaf from Floor 1 mining drops.", "gather", "f1_copperleaf", 1, 5, 400L, 60L, "mining", "first_haven_path", "Mine copper or iron ores on Floor 1 until Copperleaf drops."));
        this.addQuest(new QuestDefinition("fhp-04-camp-sweep", "First Haven Path IV: Camp Sweep", "Find a Floor 1 camp and make sure the route is safe.", "explore", "camp", 1, 1, 300L, 45L, "exploration", "first_haven_path", "Travel outward from a village and look for a small camp."));
        this.addQuest(new QuestDefinition("fhp-05-undead-pressure", "First Haven Path V: Undead Pressure", "Defeat ten Floor 1 zombies before they overrun the road camps.", "kill", "zombie", 1, 10, 450L, 70L, "swordsmanship", "first_haven_path", "Hunt low-risk zombies on Floor 1."));
        this.addQuest(new QuestDefinition("fhp-06-gatekeeper-prep", "First Haven Path VI: Gatekeeper Preparations", "Turn in three Floor 1 Gate Splinters before challenging the First Gatekeeper.", "turnin", "f1_gate_splinter", 1, 3, 500L, 75L, "exploration", "first_haven_path", "Gate Splinters drop from rare Floor 1 mining, combat, and boss loot."));
        this.addQuest(new QuestDefinition("fhp-07-first-gate", "First Haven Path VII: The First Gate", "Defeat the Floor 1 Gatekeeper and prove you are ready for the next floor.", "boss", "floor_1_boss", 1, 1, 900L, 120L, "swordsmanship", "first_haven_path", "Use /cmmo boss at the arena, then /cmmo boss start when ready."));
        this.addQuest(new QuestDefinition("second-floor-survey", "Second Floor Survey", "Discover two Floor 2 landmarks and begin mapping the first true adventure floor.", "explore", "landmark", 2, 2, 900L, 100L, "exploration", "floor_2", "Search for large landmarks after entering Floor 2."));
    }

    private void loadConfiguredQuests() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("mmo.quests.definitions");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection quest = section.getConfigurationSection(key);
            if (quest == null) {
                continue;
            }
            this.addQuest(new QuestDefinition(
                    key.toLowerCase(Locale.ROOT),
                    quest.getString("title", key),
                    quest.getString("description", "A CrownsMMO quest."),
                    quest.getString("objective.type", "explore").toLowerCase(Locale.ROOT),
                    quest.getString("objective.subject", "*").toLowerCase(Locale.ROOT),
                    quest.getInt("floor", 0),
                    Math.max(1, quest.getInt("objective.amount", 1)),
                    Math.max(0L, quest.getLong("rewards.currency", 0L)),
                    Math.max(0L, quest.getLong("rewards.xp", 0L)),
                    quest.getString("rewards.skill", "exploration").toLowerCase(Locale.ROOT),
                    quest.getString("line", quest.getString("quest-line", "custom")).toLowerCase(Locale.ROOT),
                    quest.getString("hint", "Open the quest detail page for guidance.")
            ));
        }
    }

    private void addQuest(QuestDefinition quest) {
        this.quests.put(quest.key(), quest);
    }

    private void handleFallbackExplore(Player player) {
        int floor = this.plugin.getFloorManager().getFloorNumber(player.getWorld());
        if (floor <= 0 || player.getLocation().distance(player.getWorld().getSpawnLocation()) < 64.0D) {
            return;
        }
        int cellX = Math.floorDiv(player.getLocation().getBlockX(), 128);
        int cellZ = Math.floorDiv(player.getLocation().getBlockZ(), 128);
        for (QuestDefinition quest : this.getQuests()) {
            if (!"explore".equals(quest.objectiveType()) || quest.isFloorSpecificMismatch(floor)) {
                continue;
            }
            String pointKey = "fallback:" + quest.key() + ":" + cellX + ":" + cellZ;
            if (this.markDiscovered(player, quest.key(), pointKey)) {
                this.addProgress(player, quest, 1);
                player.sendMessage(Component.text("Quest route marked: Floor " + floor + " exploration milestone.", NamedTextColor.LIGHT_PURPLE));
            }
        }
    }

    public record QuestDefinition(
            String key,
            String title,
            String description,
            String objectiveType,
            String subject,
            int floor,
            int amount,
            long rewardCurrency,
            long rewardXp,
            String rewardSkill,
            String questLine,
            String hint
    ) {
        public boolean isFloorSpecificMismatch(int currentFloor) {
            return this.floor > 0 && this.floor != currentFloor;
        }

        public boolean subjectMatches(String value) {
            return "*".equals(this.subject) || this.subject.equalsIgnoreCase(value);
        }

        public String objectiveLine() {
            return this.objectiveType + " " + this.amount + "x " + this.subject + (this.floor > 0 ? " on Floor " + this.floor : "");
        }
    }

    public record QuestProgress(String questKey, String status, int progress, long completedAt) {
        public boolean isCompleted() {
            return "completed".equalsIgnoreCase(this.status);
        }
    }

    public record QuestView(QuestDefinition quest, QuestProgress progress) {
    }

    private String floorWorldName(Player player, int floor) {
        if (floor <= 0) {
            return player.getWorld().getName();
        }
        var configuredFloor = this.plugin.getFloorManager().getFloor(floor);
        return configuredFloor == null ? player.getWorld().getName() : configuredFloor.worldName();
    }
}
