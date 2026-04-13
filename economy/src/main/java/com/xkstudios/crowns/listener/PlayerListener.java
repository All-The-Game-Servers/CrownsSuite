package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.data.PlayerData;
import com.xkstudios.crowns.economy.Currency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class PlayerListener implements Listener {
    private final CrownsPlugin plugin;
    private static final Map<UUID, String[]> CUSTOM_TAGS = Map.of(UUID.fromString("93aeb51a-1387-4b73-9f09-4ac8779d3d86"), new String[]{"Owner", "#ff1e70"});

    public PlayerListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData pd = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        this.plugin.getAntiExploit().recordMovement(player.getUniqueId());
        this.plugin.getPlaytimeManager().handleJoin(player);
        this.applyTag(player);
        if (!this.plugin.getConfig().getBoolean("daily-login.enabled", true)) {
            return;
        }
        long today = LocalDate.now().toEpochDay();
        if (pd.getLastLoginDay() == today) {
            return;
        }
        int streak = pd.getLoginStreak();
        streak = pd.getLastLoginDay() == today - 1L ? ++streak : 1;
        var rewards = this.plugin.getConfig().getList("daily-login.rewards");
        if (rewards == null || rewards.isEmpty()) {
            return;
        }
        int day = Math.min(streak, rewards.size()) - 1;
        long reward = ((Number)rewards.get(day)).longValue();
        pd.setLoginStreak(streak > rewards.size() ? 1 : streak);
        pd.setLastLoginDay(today);
        this.plugin.getEconomy().deposit(player, reward, "daily-login", "Daily login bonus");
        player.sendMessage(Component.text("Daily login bonus (Day " + Math.min(streak, rewards.size()) + "): +" + Currency.format(reward), NamedTextColor.GOLD));
        if (streak >= rewards.size()) {
            player.sendMessage(Component.text("Streak complete! Resets tomorrow.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.getPlaytimeManager().handleQuit(event.getPlayer());
        this.plugin.getAfkManager().clear(event.getPlayer().getUniqueId());
        this.plugin.getDataManager().evict(event.getPlayer().getUniqueId());
        this.plugin.getAntiExploit().clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            this.plugin.getAntiExploit().recordMovement(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!this.plugin.getConfig().getBoolean("economy.death-penalty.enabled", true)) {
            return;
        }
        Player player = event.getEntity();
        if (this.plugin.getConfig().getBoolean("economy.death-penalty.pvp-exempt", true) && player.getKiller() != null) {
            return;
        }
        PlayerData pd = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        double pct = this.plugin.getConfig().getDouble("economy.death-penalty.percent", 0.02);
        long maxLoss = this.plugin.getConfig().getLong("economy.death-penalty.max-loss", 500L);
        long loss = Math.min((long)((double)pd.getBalance() * pct), maxLoss);
        if (loss > 0L) {
            this.plugin.getEconomy().withdraw(player, loss, "death-penalty", "Death penalty");
            player.sendMessage(Component.text("You lost " + Currency.format(loss) + " on death.", NamedTextColor.RED));
        }
    }

    private void applyTag(Player player) {
        refreshTag(this.plugin, player);
    }

    public static void refreshTag(CrownsPlugin plugin, Player player) {
        Component prefixComponent = Component.empty();
        String[] tag = CUSTOM_TAGS.get(player.getUniqueId());
        if (tag != null) {
            prefixComponent = prefixComponent.append(Component.text("[" + tag[0] + "] ", TextColor.fromHexString(tag[1])));
        }
        String roleBadge = plugin.getModerationManager().getRoleBadge(player.getUniqueId());
        TextColor roleColor = plugin.getModerationManager().getRoleColor(player.getUniqueId());
        if (roleBadge != null) {
            prefixComponent = prefixComponent.append(Component.text("[" + roleBadge + "] ", roleColor == null ? NamedTextColor.AQUA : roleColor));
        }
        if (plugin.getAfkManager().isManualAfk(player.getUniqueId())) {
            prefixComponent = prefixComponent.append(Component.text("[AFK] ", NamedTextColor.YELLOW));
        }
        player.displayName(prefixComponent.append(Component.text(player.getName(), NamedTextColor.WHITE)));
        player.playerListName(prefixComponent.append(Component.text(player.getName(), NamedTextColor.WHITE)));
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ce_" + player.getName().substring(0, Math.min(player.getName().length(), 12));
        Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
        }
        team.prefix(prefixComponent);
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        boolean hasCustomTag = CUSTOM_TAGS.containsKey(player.getUniqueId());
        boolean isAfk = this.plugin.getAfkManager().isManualAfk(player.getUniqueId());
        if (!hasCustomTag && !isAfk) {
            return;
        }
        Component prefix = Component.empty();
        if (hasCustomTag) {
            String[] tag = CUSTOM_TAGS.get(player.getUniqueId());
            prefix = prefix.append(Component.text("[" + tag[0] + "] ", TextColor.fromHexString(tag[1])));
        }
        if (isAfk) {
            prefix = prefix.append(Component.text("[AFK] ", NamedTextColor.YELLOW));
        }
        event.setCancelled(true);
        Bukkit.getServer().sendMessage(prefix
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(": " + event.getMessage(), NamedTextColor.GRAY)));
    }
}
