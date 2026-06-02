package com.xkstudios.crowns.api.action;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class ParticlePatternService {
    private final JavaPlugin plugin;

    public ParticlePatternService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void burst(Location center, Particle particle, int count, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(particle, center, count, radius, radius, radius, 0.02);
    }

    public void dustBurst(Location center, Color color, int count, double radius, float size) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.DUST, center, count, radius, radius, radius, 0.02, new Particle.DustOptions(color, size));
    }

    public void ring(Location center, Particle particle, double radius, int points) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int safePoints = Math.max(8, points);
        for (int i = 0; i < safePoints; i++) {
            double angle = (Math.PI * 2.0D * i) / safePoints;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.05D, Math.sin(angle) * radius);
            world.spawnParticle(particle, point, 1, 0.01D, 0.01D, 0.01D, 0.0D);
        }
    }

    public void starfield(Player player, int ticks, double radius) {
        new BukkitRunnable() {
            private int age;

            @Override
            public void run() {
                if (!player.isOnline() || this.age++ > ticks) {
                    this.cancel();
                    return;
                }
                Location base = player.getLocation().add(0.0D, 1.0D, 0.0D);
                World world = base.getWorld();
                if (world == null) {
                    return;
                }
                for (int i = 0; i < 7; i++) {
                    double angle = (this.age * 0.45D) + (i * Math.PI * 2.0D / 7.0D);
                    double y = 0.25D + ((i % 3) * 0.35D);
                    Location point = base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.ENCHANT, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
                    world.spawnParticle(Particle.END_ROD, point, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }.runTaskTimer(this.plugin, 0L, 2L);
    }

    public void trail(Location start, Vector direction, Particle particle, double range, double step) {
        World world = start.getWorld();
        if (world == null) {
            return;
        }
        Vector normalized = direction.clone().normalize();
        for (double distance = 0.0D; distance <= range; distance += Math.max(0.2D, step)) {
            Location point = start.clone().add(normalized.clone().multiply(distance));
            world.spawnParticle(particle, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }
}
