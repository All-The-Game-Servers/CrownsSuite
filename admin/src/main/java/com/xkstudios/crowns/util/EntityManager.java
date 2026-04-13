/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.Item
 *  org.bukkit.entity.Monster
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.scheduler.BukkitTask
 */
package com.xkstudios.crowns.util;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class EntityManager {
    private final CrownsPlugin plugin;
    private BukkitTask sweepTask;

    public EntityManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.startSweepCycle();
    }

    private void startSweepCycle() {
        if (!this.plugin.getConfig().getBoolean("entity-management.sweeper.enabled", true)) {
            return;
        }
        int intervalMinutes = this.plugin.getConfig().getInt("entity-management.sweeper.interval-minutes", 5);
        int warningSeconds = this.plugin.getConfig().getInt("entity-management.sweeper.warning-seconds", 30);
        long intervalTicks = (long)intervalMinutes * 20L * 60L;
        long warningTicks = (long)warningSeconds * 20L;
        this.sweepTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            int groundItems = this.countGroundItems();
            if (groundItems < this.plugin.getConfig().getInt("entity-management.sweeper.min-items-to-sweep", 10)) {
                return;
            }
            Bukkit.broadcast((Component)Component.text((String)("Ground items clearing in " + warningSeconds + " seconds! (" + groundItems + " items)"), (TextColor)NamedTextColor.RED));
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                int cleared = this.sweepGroundItems();
                if (cleared > 0) {
                    Bukkit.broadcast((Component)Component.text((String)("Cleared " + cleared + " ground items."), (TextColor)NamedTextColor.GRAY));
                }
            }, warningTicks);
        }, intervalTicks, intervalTicks);
    }

    private int countGroundItems() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item)) continue;
                ++count;
            }
        }
        return count;
    }

    private int sweepGroundItems() {
        int cleared = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item)) continue;
                Item item = (Item)entity;
                if (entity.getTicksLived() < 600) continue;
                String key = entity.getWorld().getName() + ":" + entity.getLocation().getBlockX() + ":" + entity.getLocation().getBlockY() + ":" + entity.getLocation().getBlockZ();
                if (this.plugin.getShopManager().getAt(key) != null) continue;
                entity.remove();
                ++cleared;
            }
        }
        return cleared;
    }

    public int clearItems(World world, Location center, int radius) {
        int cleared = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Item) || radius > 0 && entity.getLocation().distanceSquared(center) > (double)(radius * radius)) continue;
            entity.remove();
            ++cleared;
        }
        return cleared;
    }

    public int clearMobs(World world, Location center, int radius) {
        int cleared = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Monster) || entity.getCustomName() != null || radius > 0 && entity.getLocation().distanceSquared(center) > (double)(radius * radius)) continue;
            entity.remove();
            ++cleared;
        }
        return cleared;
    }

    public String getEntityBreakdown(World world) {
        TreeMap<String, Integer> counts = new TreeMap<String, Integer>();
        for (Entity entity : world.getEntities()) {
            String type = entity.getType().name();
            counts.merge(type, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Total: ").append(world.getEntities().size()).append("\n");
        counts.entrySet().stream().sorted((a, b) -> (Integer)b.getValue() - (Integer)a.getValue()).limit(15L).forEach(e -> sb.append("  ").append((String)e.getKey()).append(": ").append(e.getValue()).append("\n"));
        return sb.toString();
    }

    public void shutdown() {
        if (this.sweepTask != null) {
            this.sweepTask.cancel();
        }
    }
}

