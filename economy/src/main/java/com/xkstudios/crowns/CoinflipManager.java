/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 */
package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class CoinflipManager {
    private final CrownsPlugin plugin;
    private final Map<UUID, CoinflipChallenge> pending = new ConcurrentHashMap<UUID, CoinflipChallenge>();

    public CoinflipManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer((Plugin)plugin, () -> {
            long now = System.currentTimeMillis();
            this.pending.values().removeIf(c -> now - c.createdAt > 60000L);
        }, 1200L, 1200L);
    }

    public boolean challenge(Player challenger, Player target, long amount) {
        if (challenger.equals((Object)target)) {
            return false;
        }
        if (this.pending.containsKey(challenger.getUniqueId())) {
            return false;
        }
        if (this.plugin.getEconomy().getBalance(challenger) < amount) {
            return false;
        }
        CoinflipChallenge c = new CoinflipChallenge(challenger.getUniqueId(), target.getUniqueId(), amount);
        this.pending.put(challenger.getUniqueId(), c);
        target.sendMessage(((TextComponent)Component.text((String)(challenger.getName() + " challenged you to a coinflip for "), (TextColor)NamedTextColor.GOLD).append((Component)Component.text((String)Currency.format(amount), (TextColor)NamedTextColor.YELLOW, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)"! Type /ce coinflip accept to accept.", (TextColor)NamedTextColor.GOLD)));
        return true;
    }

    public boolean accept(Player accepter) {
        CoinflipChallenge challenge = null;
        UUID challengerUuid = null;
        for (Map.Entry<UUID, CoinflipChallenge> entry : this.pending.entrySet()) {
            if (!entry.getValue().target.equals(accepter.getUniqueId())) continue;
            challenge = entry.getValue();
            challengerUuid = entry.getKey();
            break;
        }
        if (challenge == null) {
            return false;
        }
        this.pending.remove(challengerUuid);
        Player challenger = Bukkit.getPlayer((UUID)challenge.challenger);
        if (challenger == null || !challenger.isOnline()) {
            return false;
        }
        long amount = challenge.amount;
        if (this.plugin.getEconomy().getBalance(challenger) < amount) {
            accepter.sendMessage((Component)Component.text((String)(challenger.getName() + " can no longer afford the bet."), (TextColor)NamedTextColor.RED));
            return false;
        }
        if (this.plugin.getEconomy().getBalance(accepter) < amount) {
            accepter.sendMessage((Component)Component.text((String)"You can't afford this bet.", (TextColor)NamedTextColor.RED));
            return false;
        }
        this.plugin.getEconomy().withdraw(challenger, amount);
        this.plugin.getEconomy().withdraw(accepter, amount);
        boolean challengerWins = new Random().nextBoolean();
        Player winner = challengerWins ? challenger : accepter;
        Player loser = challengerWins ? accepter : challenger;
        long payout = amount * 2L;
        this.plugin.getEconomy().deposit(winner, payout);
        Component msg = ((TextComponent)((TextComponent)((TextComponent)((TextComponent)((TextComponent)Component.text((String)"COINFLIP: ", (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).append((Component)Component.text((String)winner.getName(), (TextColor)NamedTextColor.GREEN, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)" beat ", (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)loser.getName(), (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)" and won ", (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)Currency.format(payout), (TextColor)NamedTextColor.YELLOW, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)"!", (TextColor)NamedTextColor.GRAY));
        Bukkit.broadcast((Component)msg);
        return true;
    }

    public boolean deny(Player denier) {
        for (Map.Entry<UUID, CoinflipChallenge> entry : this.pending.entrySet()) {
            if (!entry.getValue().target.equals(denier.getUniqueId())) continue;
            this.pending.remove(entry.getKey());
            Player challenger = Bukkit.getPlayer((UUID)entry.getValue().challenger);
            if (challenger != null) {
                challenger.sendMessage((Component)Component.text((String)(denier.getName() + " declined your coinflip."), (TextColor)NamedTextColor.RED));
            }
            return true;
        }
        return false;
    }

    public boolean hasPendingChallenge(UUID uuid) {
        return this.pending.containsKey(uuid);
    }

    public static class CoinflipChallenge {
        public final UUID challenger;
        public final UUID target;
        public final long amount;
        public final long createdAt;

        public CoinflipChallenge(UUID challenger, UUID target, long amount) {
            this.challenger = challenger;
            this.target = target;
            this.amount = amount;
            this.createdAt = System.currentTimeMillis();
        }
    }
}

