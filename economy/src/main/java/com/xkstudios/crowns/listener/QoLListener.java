/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.World
 *  org.bukkit.World$Environment
 *  org.bukkit.entity.EntityType
 *  org.bukkit.entity.LivingEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.entity.EntityDeathEvent
 *  org.bukkit.event.entity.PlayerDeathEvent
 *  org.bukkit.event.player.PlayerBedEnterEvent
 *  org.bukkit.event.player.PlayerBedEnterEvent$BedEnterResult
 *  org.bukkit.event.player.PlayerPortalEvent
 *  org.bukkit.event.player.PlayerTeleportEvent
 *  org.bukkit.event.player.PlayerTeleportEvent$TeleportCause
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.inventory.meta.SkullMeta
 *  org.bukkit.plugin.Plugin
 */
package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

public class QoLListener
implements Listener {
    private final CrownsPlugin plugin;
    private static final Map<EntityType, String> MOB_HEAD_NAMES = Map.ofEntries(Map.entry(EntityType.ZOMBIE, "Zombie"), Map.entry(EntityType.SKELETON, "Skeleton"), Map.entry(EntityType.CREEPER, "Creeper"), Map.entry(EntityType.SPIDER, "Spider"), Map.entry(EntityType.ENDERMAN, "Enderman"), Map.entry(EntityType.BLAZE, "Blaze"), Map.entry(EntityType.WITHER_SKELETON, "Wither Skeleton"), Map.entry(EntityType.WITCH, "Witch"), Map.entry(EntityType.PIGLIN, "Piglin"), Map.entry(EntityType.PIGLIN_BRUTE, "Piglin Brute"), Map.entry(EntityType.PILLAGER, "Pillager"), Map.entry(EntityType.VINDICATOR, "Vindicator"), Map.entry(EntityType.EVOKER, "Evoker"), Map.entry(EntityType.DROWNED, "Drowned"), Map.entry(EntityType.HUSK, "Husk"), Map.entry(EntityType.STRAY, "Stray"), Map.entry(EntityType.PHANTOM, "Phantom"), Map.entry(EntityType.GUARDIAN, "Guardian"), Map.entry(EntityType.ELDER_GUARDIAN, "Elder Guardian"), Map.entry(EntityType.WARDEN, "Warden"), Map.entry(EntityType.GHAST, "Ghast"), Map.entry(EntityType.MAGMA_CUBE, "Magma Cube"), Map.entry(EntityType.SLIME, "Slime"), Map.entry(EntityType.RAVAGER, "Ravager"));
    private static final Map<EntityType, Material> VANILLA_HEADS = Map.of(EntityType.ZOMBIE, Material.ZOMBIE_HEAD, EntityType.SKELETON, Material.SKELETON_SKULL, EntityType.CREEPER, Material.CREEPER_HEAD, EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SKULL);

    public QoLListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        Player sleeper = event.getPlayer();
        World world = sleeper.getWorld();
        Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            if (!sleeper.isSleeping()) {
                return;
            }
            int required = this.plugin.getConfig().getInt("sleep.required-players", 3);
            List<Player> worldPlayers = world.getPlayers();
            int effectiveRequired = Math.min(required, worldPlayers.size());
            long sleeping = worldPlayers.stream().filter(Player::isSleeping).count();
            long nonAfkOnline = worldPlayers.stream().filter(p -> !this.plugin.getAntiExploit().isAfk(p.getUniqueId())).count();
            effectiveRequired = (int)Math.min((long)effectiveRequired, nonAfkOnline);
            long needed = (long)(effectiveRequired = Math.max(1, effectiveRequired)) - sleeping;
            if (needed <= 0L) {
                world.setTime(0L);
                world.setStorm(false);
                world.setThundering(false);
                Bukkit.broadcast((Component)Component.text((String)("Good morning! " + sleeping + " players slept through the night."), (TextColor)NamedTextColor.GOLD));
            } else {
                Bukkit.broadcast((Component)Component.text((String)(sleeper.getName() + " is sleeping. " + sleeping + "/" + effectiveRequired + " needed to skip night. " + needed + " more!"), (TextColor)NamedTextColor.GOLD));
            }
        }, 2L);
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getPlayer().hasPermission("crowns.admin")) {
            return;
        }
        World.Environment dest = null;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            dest = World.Environment.NETHER;
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            dest = World.Environment.THE_END;
        }
        if (dest == null) {
            return;
        }
        if (this.isLocked(dest)) {
            event.setCancelled(true);
            String name = dest == World.Environment.NETHER ? "The Nether" : "The End";
            String message = this.plugin.getEventManager() != null ? this.plugin.getEventManager().getDimensionLockMessage(dest) : null;
            event.getPlayer().sendMessage((Component)Component.text((String)(message == null ? name + " is not yet accessible." : message), (TextColor)NamedTextColor.RED));
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getPlayer().hasPermission("crowns.admin")) {
            return;
        }
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (event.getFrom().getWorld() != null && event.getFrom().getWorld().equals((Object)event.getTo().getWorld())) {
            return;
        }
        World.Environment dest = event.getTo().getWorld().getEnvironment();
        if (this.isLocked(dest)) {
            event.setCancelled(true);
            String name = dest == World.Environment.NETHER ? "The Nether" : "The End";
            String message = this.plugin.getEventManager() != null ? this.plugin.getEventManager().getDimensionLockMessage(dest) : null;
            event.getPlayer().sendMessage((Component)Component.text((String)(message == null ? name + " is not yet accessible." : message), (TextColor)NamedTextColor.RED));
        }
    }

    private boolean isLocked(World.Environment env) {
        if (env == World.Environment.NETHER) {
            return this.plugin.getConfig().getBoolean("dimensions.nether-locked", true);
        }
        if (env == World.Environment.THE_END) {
            return this.plugin.getConfig().getBoolean("dimensions.end-locked", true);
        }
        return false;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta)head.getItemMeta();
        meta.setOwningPlayer((OfflinePlayer)victim);
        if (killer != null && !killer.equals((Object)victim)) {
            meta.displayName((Component)Component.text((String)(victim.getName() + "'s Head"), (TextColor)NamedTextColor.RED, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
            meta.lore(List.of(Component.text((String)("Slain by " + killer.getName()), (TextColor)NamedTextColor.GRAY), Component.text((String)"Trophy Kill", (TextColor)NamedTextColor.DARK_PURPLE, (TextDecoration[])new TextDecoration[]{TextDecoration.ITALIC})));
            head.setItemMeta((ItemMeta)meta);
            victim.getWorld().dropItemNaturally(victim.getLocation(), head);
            killer.sendMessage((Component)Component.text((String)("You claimed " + victim.getName() + "'s head as a trophy!"), (TextColor)NamedTextColor.RED));
        } else {
            meta.displayName((Component)Component.text((String)(victim.getName() + "'s Head"), (TextColor)NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text((String)"Dropped on death", (TextColor)NamedTextColor.GRAY)));
            head.setItemMeta((ItemMeta)meta);
            victim.getWorld().dropItemNaturally(victim.getLocation(), head);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!this.plugin.getConfig().getBoolean("mob-heads.enabled", true)) {
            return;
        }
        if (event.getEntity().getKiller() == null) {
            return;
        }
        if (event.getEntity() instanceof Player) {
            return;
        }
        LivingEntity entity = event.getEntity();
        String mobName = MOB_HEAD_NAMES.get(entity.getType());
        if (mobName == null) {
            return;
        }
        double chance = this.plugin.getConfig().getDouble("mob-heads.drop-chance", 0.05);
        if (Math.random() > chance) {
            return;
        }
        Material vanillaHead = VANILLA_HEADS.get(entity.getType());
        ItemStack head = vanillaHead != null ? new ItemStack(vanillaHead) : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.displayName((Component)Component.text((String)(mobName + " Head"), (TextColor)NamedTextColor.YELLOW));
        meta.lore(List.of(Component.text((String)("Killed by " + event.getEntity().getKiller().getName()), (TextColor)NamedTextColor.GRAY), Component.text((String)"Rare Drop", (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.ITALIC})));
        head.setItemMeta(meta);
        entity.getWorld().dropItemNaturally(entity.getLocation(), head);
        Player killer = event.getEntity().getKiller();
        killer.sendMessage((Component)Component.text((String)("Rare drop! " + mobName + " Head!"), (TextColor)NamedTextColor.GOLD));
    }
}
