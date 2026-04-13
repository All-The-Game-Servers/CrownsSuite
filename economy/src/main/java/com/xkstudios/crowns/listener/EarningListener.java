/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.EntityType
 *  org.bukkit.entity.Item
 *  org.bukkit.entity.LivingEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.BlockBreakEvent
 *  org.bukkit.event.entity.EntityDeathEvent
 *  org.bukkit.event.player.PlayerFishEvent
 *  org.bukkit.event.player.PlayerFishEvent$State
 */
package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

public class EarningListener
implements Listener {
    private final CrownsPlugin plugin;
    private static final Map<Material, String> ORES = Map.ofEntries(Map.entry(Material.COAL_ORE, "coal"), Map.entry(Material.DEEPSLATE_COAL_ORE, "coal"), Map.entry(Material.IRON_ORE, "iron"), Map.entry(Material.DEEPSLATE_IRON_ORE, "iron"), Map.entry(Material.GOLD_ORE, "gold"), Map.entry(Material.DEEPSLATE_GOLD_ORE, "gold"), Map.entry(Material.COPPER_ORE, "copper"), Map.entry(Material.DEEPSLATE_COPPER_ORE, "copper"), Map.entry(Material.DIAMOND_ORE, "diamond"), Map.entry(Material.DEEPSLATE_DIAMOND_ORE, "diamond"), Map.entry(Material.EMERALD_ORE, "emerald"), Map.entry(Material.DEEPSLATE_EMERALD_ORE, "emerald"), Map.entry(Material.LAPIS_ORE, "lapis"), Map.entry(Material.DEEPSLATE_LAPIS_ORE, "lapis"), Map.entry(Material.REDSTONE_ORE, "redstone"), Map.entry(Material.DEEPSLATE_REDSTONE_ORE, "redstone"), Map.entry(Material.ANCIENT_DEBRIS, "ancient-debris"), Map.entry(Material.NETHER_GOLD_ORE, "gold"));
    private static final Map<EntityType, String> MOBS = Map.of(EntityType.ZOMBIE, "zombie", EntityType.SKELETON, "skeleton", EntityType.SPIDER, "spider", EntityType.CREEPER, "creeper", EntityType.ENDERMAN, "enderman", EntityType.BLAZE, "blaze", EntityType.WITHER_SKELETON, "wither-skeleton", EntityType.PIGLIN_BRUTE, "piglin-brute");

    public EarningListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMine(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String type = ORES.get(event.getBlock().getType());
        if (type == null) {
            return;
        }
        if (this.plugin.getAntiExploit().isAfk(player.getUniqueId())) {
            return;
        }
        if (!this.plugin.getAntiExploit().canEarn(player.getUniqueId(), "mining")) {
            return;
        }
        long base = this.plugin.getEconomy().getMiningReward(type);
        if (base <= 0L) {
            return;
        }
        var pd = this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName());
        pd.addMiningToday();
        double mult = pd.getDailyMultiplier("mining", this.plugin.getConfig().getInt("economy.daily-limits.mining-full-reward", 150), this.plugin.getConfig().getInt("economy.daily-limits.mining-half-reward", 300));
        long reward = (long)((double)base * mult);
        if (reward > 0L) {
            this.plugin.getEconomy().reward(player, reward, "Mining: " + type);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onKill(EntityDeathEvent event) {
        String mobType;
        Player player = event.getEntity().getKiller();
        if (!(player instanceof Player)) {
            return;
        }
        Player player2 = player;
        if (this.plugin.getAntiExploit().isAfk(player2.getUniqueId())) {
            return;
        }
        if (!this.plugin.getAntiExploit().canEarn(player2.getUniqueId(), "combat")) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (victim instanceof Player) {
            Player killed = (Player)victim;
            if (!victim.equals((Object)player2)) {
                long reward = this.plugin.getEconomy().getCombatReward("player-kill");
                if (reward > 0L) {
                    this.plugin.getEconomy().reward(player2, reward, "PvP");
                }
                return;
            }
        }
        if ((mobType = MOBS.get(victim.getType())) == null) {
            return;
        }
        long base = this.plugin.getEconomy().getCombatReward(mobType);
        if (base <= 0L) {
            return;
        }
        double dimMult = this.plugin.getAntiExploit().getDiminishing(player2.getUniqueId(), mobType);
        var pd = this.plugin.getDataManager().getOrCreate(player2.getUniqueId(), player2.getName());
        pd.addCombatToday();
        double dayMult = pd.getDailyMultiplier("combat", this.plugin.getConfig().getInt("economy.daily-limits.combat-full-reward", 100), this.plugin.getConfig().getInt("economy.daily-limits.combat-half-reward", 200));
        long reward = (long)((double)base * dimMult * dayMult);
        if (reward > 0L) {
            this.plugin.getEconomy().reward(player2, reward, "Combat: " + mobType);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onFish(PlayerFishEvent event) {
        long reward;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        if (this.plugin.getAntiExploit().isAfk(player.getUniqueId())) {
            return;
        }
        String type = "fish";
        Entity entity = event.getCaught();
        if (entity instanceof Item) {
            Item item = (Item)entity;
            Material mat = item.getItemStack().getType();
            if (mat == Material.BOW || mat == Material.ENCHANTED_BOOK || mat == Material.NAME_TAG || mat == Material.SADDLE || mat == Material.NAUTILUS_SHELL) {
                type = "treasure";
            } else if (mat == Material.LILY_PAD || mat == Material.BOWL || mat == Material.LEATHER || mat == Material.STICK || mat == Material.STRING || mat == Material.TRIPWIRE_HOOK) {
                type = "junk";
            }
        }
        if ((reward = this.plugin.getEconomy().getFishingReward(type)) > 0L) {
            this.plugin.getEconomy().reward(player, reward, "Fishing");
        }
    }
}
