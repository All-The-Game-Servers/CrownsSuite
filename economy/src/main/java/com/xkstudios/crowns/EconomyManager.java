package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.data.PlayerData;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public class EconomyManager {
    private final CrownsPlugin plugin;

    public EconomyManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public long getBalance(Player player) {
        return this.plugin.getDataManager().getOrCreate(player.getUniqueId(), player.getName()).getBalance();
    }

    public boolean withdraw(Player player, long amount) {
        return this.withdraw(player, amount, null, null);
    }

    public boolean withdraw(Player player, long amount, String category, String detail) {
        return this.withdraw(player.getUniqueId(), player.getName(), amount, category, detail);
    }

    public boolean withdraw(UUID playerUuid, String playerName, long amount, String category, String detail) {
        PlayerData data = this.plugin.getDataManager().getOrCreate(playerUuid, playerName);
        if (!data.withdraw(amount)) {
            return false;
        }
        if (category != null) {
            this.plugin.getEconomyLedgerManager().recordSink(playerUuid, data.getName(), category, amount, detail);
        }
        return true;
    }

    public void deposit(Player player, long amount) {
        this.deposit(player, amount, null, null);
    }

    public void deposit(Player player, long amount, String category, String detail) {
        this.deposit(player.getUniqueId(), player.getName(), amount, category, detail);
    }

    public void deposit(UUID playerUuid, String playerName, long amount, String category, String detail) {
        if (amount <= 0L) {
            return;
        }
        PlayerData pd = this.plugin.getDataManager().getOrCreate(playerUuid, playerName);
        long max = this.plugin.getConfig().getLong("economy.max-balance", 100000000L);
        long credited = Math.max(0L, Math.min(amount, max - pd.getBalance()));
        if (credited <= 0L) {
            return;
        }
        pd.addBalance(credited);
        if (category != null) {
            this.plugin.getEconomyLedgerManager().recordSource(playerUuid, pd.getName(), category, credited, detail);
        }
    }

    public boolean transfer(Player from, Player to, long amount) {
        double taxRate = this.plugin.getConfig().getDouble("economy.taxes.transaction-tax", 0.03);
        long tax = (long)((double)amount * taxRate);
        long total = amount + tax;
        if (!this.withdraw(from, total, "player-payments", "Sent to " + to.getName())) {
            return false;
        }
        this.deposit(to, amount, "player-payments", "Received from " + from.getName());
        if (tax > 0L) {
            this.plugin.getEconomyLedgerManager().recordSink(from.getUniqueId(), from.getName(), "transaction-tax", tax, "Tax for payment to " + to.getName());
        }
        return true;
    }

    public void reward(Player player, long amount, String reason) {
        if (amount <= 0L) {
            return;
        }
        this.deposit(player, amount, this.categoryForReason(reason), reason);
        player.sendActionBar(Component.text("+" + Currency.format(amount), NamedTextColor.GOLD));
    }

    public void adminGive(Player actor, Player target, long amount) {
        this.deposit(target, amount, "admin-adjustments", "Given by " + actor.getName());
    }

    public boolean adminTake(Player actor, Player target, long amount) {
        return this.withdraw(target, amount, "admin-adjustments", "Taken by " + actor.getName());
    }

    public void adminSet(Player actor, Player target, long amount) {
        PlayerData data = this.plugin.getDataManager().getOrCreate(target.getUniqueId(), target.getName());
        long previous = data.getBalance();
        data.setBalance(amount);
        long delta = amount - previous;
        if (delta > 0L) {
            this.plugin.getEconomyLedgerManager().recordSource(target.getUniqueId(), target.getName(), "admin-adjustments", delta, "Set by " + actor.getName());
        } else if (delta < 0L) {
            this.plugin.getEconomyLedgerManager().recordSink(target.getUniqueId(), target.getName(), "admin-adjustments", -delta, "Set by " + actor.getName());
        }
    }

    public long getMiningReward(String type) {
        return this.plugin.getConfig().getLong("economy.rewards.mining." + type, 0L);
    }

    public long getCombatReward(String type) {
        return this.plugin.getConfig().getLong("economy.rewards.combat." + type, 0L);
    }

    public long getFarmingReward(String type) {
        return this.plugin.getConfig().getLong("economy.rewards.farming." + type, 0L);
    }

    public long getFishingReward(String type) {
        return this.plugin.getConfig().getLong("economy.rewards.fishing." + type, 0L);
    }

    private String categoryForReason(String reason) {
        if (reason == null) {
            return "misc-reward";
        }
        String normalized = reason.toLowerCase();
        if (normalized.startsWith("mining")) {
            return "mining";
        }
        if (normalized.startsWith("combat") || normalized.equals("pvp")) {
            return "combat";
        }
        if (normalized.startsWith("fishing")) {
            return "fishing";
        }
        if (normalized.startsWith("salary")) {
            return "salary";
        }
        if (normalized.startsWith("daily login")) {
            return "daily-login";
        }
        if (normalized.startsWith("job")) {
            return "job-rewards";
        }
        return "misc-reward";
    }
}
