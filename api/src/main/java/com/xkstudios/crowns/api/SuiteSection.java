package com.xkstudios.crowns.api;

import org.bukkit.Material;
import org.bukkit.entity.Player;

public record SuiteSection(
        String key,
        String displayName,
        Material icon,
        String modelPath,
        String permission,
        SuiteSectionOpener opener,
        SuiteBadgeProvider badgeProvider
) {
    public boolean isVisibleTo(Player player) {
        return player != null
                && this.opener != null
                && (this.permission == null
                || this.permission.isBlank()
                || player.hasPermission(this.permission)
                || player.isOp());
    }

    public String badge(Player player) {
        return this.badgeProvider == null ? null : this.badgeProvider.getBadge(player);
    }
}
