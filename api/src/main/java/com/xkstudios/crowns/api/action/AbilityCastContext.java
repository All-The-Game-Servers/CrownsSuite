package com.xkstudios.crowns.api.action;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public record AbilityCastContext(
        Player player,
        InputGesture triggeringGesture,
        GestureSequence matchedSequence,
        ItemStack itemInHand,
        Location origin,
        Vector direction
) {
}
