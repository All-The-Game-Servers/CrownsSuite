package com.xkstudios.crowns.api;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface SuiteBadgeProvider {
    String getBadge(Player player);
}
