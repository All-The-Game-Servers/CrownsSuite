package com.xkstudios.crowns.api.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class TargetingHelper {
    private TargetingHelper() {
    }

    public static LivingEntity rayLiving(Player player, double range, double radius, Predicate<LivingEntity> filter) {
        Location start = player.getEyeLocation();
        Vector direction = start.getDirection().normalize();
        RayTraceResult result = player.getWorld().rayTraceEntities(start, direction, range, radius, entity -> {
            if (!(entity instanceof LivingEntity living) || living.equals(player)) {
                return false;
            }
            return filter == null || filter.test(living);
        });
        return result == null || !(result.getHitEntity() instanceof LivingEntity living) ? null : living;
    }

    public static List<LivingEntity> coneLiving(Player player, double range, double degrees, Predicate<LivingEntity> filter) {
        World world = player.getWorld();
        Location origin = player.getLocation().add(0.0D, 1.0D, 0.0D);
        Vector facing = player.getEyeLocation().getDirection().normalize();
        double minDot = Math.cos(Math.toRadians(Math.max(1.0D, degrees) / 2.0D));
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(origin, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || living.equals(player)) {
                continue;
            }
            Vector toTarget = living.getLocation().add(0.0D, 1.0D, 0.0D).toVector().subtract(origin.toVector());
            if (toTarget.lengthSquared() > range * range || toTarget.lengthSquared() <= 0.01D) {
                continue;
            }
            if (facing.dot(toTarget.normalize()) < minDot) {
                continue;
            }
            if (filter == null || filter.test(living)) {
                result.add(living);
            }
        }
        result.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(origin)));
        return result;
    }

    public static List<LivingEntity> radiusLiving(Location center, double radius, Predicate<LivingEntity> filter) {
        List<LivingEntity> result = new ArrayList<>();
        World world = center.getWorld();
        if (world == null) {
            return result;
        }
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living.getLocation().distanceSquared(center) <= radius * radius && (filter == null || filter.test(living))) {
                result.add(living);
            }
        }
        return result;
    }
}
