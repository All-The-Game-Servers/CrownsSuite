package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.moderation.StaffRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class PlayerListener {
    private PlayerListener() {
    }

    public static void refreshTag(CrownsPlugin plugin, Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager() != null
                ? Bukkit.getScoreboardManager().getMainScoreboard()
                : null;
        if (scoreboard == null) {
            return;
        }

        String teamName = "ca_" + player.getUniqueId().toString().replace("-", "").substring(0, 12);
        Team team = scoreboard.getTeam(teamName);
        StaffRole role = plugin.getModerationManager().getAssignedRole(player.getUniqueId());
        if (role == null) {
            if (team != null) {
                team.removeEntry(player.getName());
            }
            return;
        }

        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.addEntry(player.getName());
        team.prefix(Component.text("[" + role.displayName() + "] ", parseColor(role.color())));
    }

    private static net.kyori.adventure.text.format.TextColor parseColor(String value) {
        if (value == null || value.isBlank()) {
            return NamedTextColor.AQUA;
        }
        TextColor parsed = TextColor.fromHexString(value);
        return parsed != null ? parsed : NamedTextColor.AQUA;
    }
}
