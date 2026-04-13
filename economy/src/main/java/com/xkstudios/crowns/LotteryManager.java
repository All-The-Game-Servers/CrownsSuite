/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 */
package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class LotteryManager {
    private final CrownsPlugin plugin;

    public LotteryManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        try (Statement s = plugin.getDataManager().getConnection().createStatement();){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS lottery (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    round INTEGER NOT NULL,\n    player_uuid TEXT NOT NULL,\n    player_name TEXT,\n    purchased_at INTEGER NOT NULL\n)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS lottery_state (\n    key TEXT PRIMARY KEY,\n    value TEXT\n)");
        }
        catch (SQLException e) {
            plugin.getLogger().warning("[Lottery] Table create failed: " + e.getMessage());
        }
        if (this.getCurrentRound() == 0) {
            this.setCurrentRound(1);
        }
    }

    public boolean buyTicket(Player player) {
        long price = this.plugin.getConfig().getLong("lottery.ticket-price", 100L);
        int maxTickets = this.plugin.getConfig().getInt("lottery.max-tickets-per-player", 10);
        int currentTickets = this.getPlayerTickets(player.getUniqueId(), this.getCurrentRound());
        if (currentTickets >= maxTickets) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(player, price, "lottery-tickets", "Lottery ticket for round #" + this.getCurrentRound())) {
            return false;
        }
        int round = this.getCurrentRound();
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("INSERT INTO lottery (round, player_uuid, player_name, purchased_at) VALUES (?, ?, ?, ?)");){
            ps.setInt(1, round);
            ps.setString(2, player.getUniqueId().toString());
            ps.setString(3, player.getName());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getEconomy().deposit(player, price, null, null);
            return false;
        }
        return true;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public int getPlayerTickets(UUID uuid, int round) {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT COUNT(*) FROM lottery WHERE round = ? AND player_uuid = ?");){
            ps.setInt(1, round);
            ps.setString(2, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 0;
            int n = rs.getInt(1);
            return n;
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return 0;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public int getTotalTickets(int round) {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT COUNT(*) FROM lottery WHERE round = ?");){
            ps.setInt(1, round);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 0;
            int n = rs.getInt(1);
            return n;
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return 0;
    }

    public long getPot(int round) {
        long price = this.plugin.getConfig().getLong("lottery.ticket-price", 100L);
        return (long)this.getTotalTickets(round) * price;
    }

    public void drawWinner() {
        PreparedStatement ps;
        int round = this.getCurrentRound();
        int totalTickets = this.getTotalTickets(round);
        if (totalTickets == 0) {
            Bukkit.broadcast((Component)Component.text((String)("Lottery Round #" + round + ": No tickets sold. No winner."), (TextColor)NamedTextColor.GRAY));
            this.setCurrentRound(round + 1);
            return;
        }
        try {
            ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT player_uuid, player_name FROM lottery WHERE round = ? ORDER BY RANDOM() LIMIT 1");
            try {
                ps.setInt(1, round);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    UUID winnerUuid = UUID.fromString(rs.getString("player_uuid"));
                    String winnerName = rs.getString("player_name");
                    long pot = this.getPot(round);
                    double sinkRate = this.plugin.getConfig().getDouble("lottery.house-cut", 0.1);
                    long houseCut = (long)((double)pot * sinkRate);
                    long payout = pot - houseCut;
                    this.plugin.getEconomy().deposit(winnerUuid, winnerName, payout, "lottery-payouts", "Lottery round #" + round);
                    if (houseCut > 0L) {
                        this.plugin.getEconomyLedgerManager().recordServerSink("lottery-house-cut", houseCut, "Lottery round #" + round);
                    }
                    Bukkit.broadcast((Component)Component.text((String)("LOTTERY ROUND #" + round), (TextColor)NamedTextColor.GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                    Bukkit.broadcast((Component)Component.text((String)(winnerName + " won " + Currency.format(payout) + " from " + totalTickets + " tickets!"), (TextColor)NamedTextColor.YELLOW));
                    Player winner = Bukkit.getPlayer((UUID)winnerUuid);
                    if (winner != null) {
                        winner.sendMessage((Component)Component.text((String)("YOU WON THE LOTTERY! +" + Currency.format(payout)), (TextColor)NamedTextColor.GREEN, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                    }
                    this.plugin.getInboxManager().push(winnerUuid, winnerName, "lottery_win",
                            "Lottery win: " + Currency.format(payout),
                            "You won lottery round #" + round + " with " + totalTickets + " tickets sold.");
                }
            }
            finally {
                if (ps != null) {
                    ps.close();
                }
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("[Lottery] Draw failed: " + e.getMessage());
        }
        try {
            ps = this.plugin.getDataManager().getConnection().prepareStatement("DELETE FROM lottery WHERE round = ?");
            try {
                ps.setInt(1, round);
                ps.executeUpdate();
            }
            finally {
                if (ps != null) {
                    ps.close();
                }
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        this.setCurrentRound(round + 1);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public int getCurrentRound() {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT value FROM lottery_state WHERE key = 'current_round'");){
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 0;
            int n = Integer.parseInt(rs.getString("value"));
            return n;
        }
        catch (Exception exception) {
            // empty catch block
        }
        return 0;
    }

    private void setCurrentRound(int round) {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("INSERT OR REPLACE INTO lottery_state (key, value) VALUES ('current_round', ?)");){
            ps.setString(1, String.valueOf(round));
            ps.executeUpdate();
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
    }

    public Map<String, Integer> getTopBuyers(int round) {
        LinkedHashMap<String, Integer> buyers = new LinkedHashMap<String, Integer>();
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT player_name, COUNT(*) as cnt FROM lottery WHERE round = ? GROUP BY player_uuid ORDER BY cnt DESC LIMIT 10");){
            ps.setInt(1, round);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                buyers.put(rs.getString("player_name"), rs.getInt("cnt"));
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return buyers;
    }
}
