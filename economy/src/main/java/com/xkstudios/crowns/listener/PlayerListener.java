package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * Legacy listener placeholder kept for compatibility with older source layout.
 */
@Deprecated
public class PlayerListener implements Listener {
    public PlayerListener(CrownsPlugin plugin) {
    }

    public static void refreshTag(CrownsPlugin plugin, Player player) {
        if (player != null) {
            player.playerListName(player.displayName());
        }
    }
}
