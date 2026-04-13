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

public class EconomyPlayerListener implements Listener {
    private static final Map<UUID, String[]> CUSTOM_TAGS = Map.of(
            UUID.fromString("93aeb51a-1387-4b73-9f09-4ac8779d3d86"),
            new String[]{"Owner", "#ff1e70"}
    );

    private final CrownsPlugin plugin;

    public EconomyPlayerListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        this.plugin.getAntiExploit().recordMovement(player.getUniqueId());
        refreshTag(player);
        if (!this.plugin.getConfig().getBoolean("daily-login.enabled", true)) {
            return;
        }
        long today = LocalDate.now().toEpochDay();
        if (data.getLastLoginDay() == today) {
            return;
        }
        int streak = data.getLastLoginDay() == today - 1L ? data.getLoginStreak() + 1 : 1;
        var rewards = this.plugin.getConfig().getList("daily-login.rewards");
        if (rewards == null || rewards.isEmpty()) {
            return;
        }
        int day = Math.min(streak, rewards.size()) - 1;
        long reward = ((Number) rewards.get(day)).longValue();
        data.setLoginStreak(streak > rewards.size() ? 1 : streak);
        data.setLastLoginDay(today);
        this.plugin.getEconomy().deposit(player, reward, "daily-login", "Daily login bonus");
        player.sendMessage(Component.text("Daily login bonus (Day " + Math.min(streak, rewards.size()) + "): +" + Currency.format(reward), NamedTextColor.GOLD));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
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
        PlayerData data = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        double pct = this.plugin.getConfig().getDouble("economy.death-penalty.percent", 0.02);
        long maxLoss = this.plugin.getConfig().getLong("economy.death-penalty.max-loss", 500L);
        long loss = Math.min((long) (data.getBalance() * pct), maxLoss);
        if (loss > 0L) {
            this.plugin.getEconomy().withdraw(player, loss, "death-penalty", "Death penalty");
            player.sendMessage(Component.text("You lost " + Currency.format(loss) + " on death.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!CUSTOM_TAGS.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        String[] tag = CUSTOM_TAGS.get(event.getPlayer().getUniqueId());
        event.setCancelled(true);
        Bukkit.getServer().sendMessage(
                Component.text("[" + tag[0] + "] ", TextColor.fromHexString(tag[1]))
                        .append(Component.text(event.getPlayer().getName(), NamedTextColor.WHITE))
                        .append(Component.text(": " + event.getMessage(), NamedTextColor.GRAY))
        );
    }

    private void refreshTag(Player player) {
        String[] tag = CUSTOM_TAGS.get(player.getUniqueId());
        if (tag == null) {
            return;
        }
        Component prefix = Component.text("[" + tag[0] + "] ", TextColor.fromHexString(tag[1]));
        player.displayName(prefix.append(Component.text(player.getName(), NamedTextColor.WHITE)));
        player.playerListName(prefix.append(Component.text(player.getName(), NamedTextColor.WHITE)));
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ce_" + player.getName().substring(0, Math.min(player.getName().length(), 12));
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.prefix(prefix);
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }
}
