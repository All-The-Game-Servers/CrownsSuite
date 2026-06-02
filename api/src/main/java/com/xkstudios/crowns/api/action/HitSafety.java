package com.xkstudios.crowns.api.action;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class HitSafety {
    private HitSafety() {
    }

    public static boolean canDamage(Player attacker, LivingEntity target, boolean pvpEnabled) {
        if (attacker == null || target == null || target.equals(attacker) || target.isDead()) {
            return false;
        }
        return pvpEnabled || !(target instanceof Player);
    }
}
