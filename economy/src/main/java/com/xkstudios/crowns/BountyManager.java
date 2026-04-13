/*
 * Decompiled with CFR 0.152.
 */
package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BountyManager {
    private final CrownsPlugin plugin;

    public BountyManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean postBounty(UUID poster, UUID target, long amount) {
        if (!this.plugin.getConfig().getBoolean("bounties.enabled", true)) {
            return false;
        }
        long min = this.plugin.getConfig().getLong("bounties.min-amount", 100L);
        if (amount < min) {
            return false;
        }
        int max = this.plugin.getConfig().getInt("bounties.max-active", 5);
        if (this.getActiveBounties().stream().filter(b -> b.poster.equals(poster)).count() >= (long)max) {
            return false;
        }
        double feeRate = this.plugin.getConfig().getDouble("economy.taxes.bounty-fee", 0.1);
        long fee = (long)((double)amount * feeRate);
        long total = amount + fee;
        String posterName = this.plugin.getDataManager().getExistingOrCreate(poster, "").getName();
        if (!this.plugin.getEconomy().withdraw(poster, posterName, amount, "bounty-posts", "Posted bounty on " + target)) {
            return false;
        }
        if (fee > 0L) {
            this.plugin.getEconomy().withdraw(poster, posterName, fee, "bounty-fees", "Bounty fee");
        }
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("INSERT INTO bounties (poster_uuid, target_uuid, amount, posted_at) VALUES (?, ?, ?, ?)");){
            ps.setString(1, poster.toString());
            ps.setString(2, target.toString());
            ps.setLong(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getEconomy().deposit(poster, posterName, total, null, null);
            return false;
        }
        return true;
    }

    public BountyClaim claimBounties(UUID killer, UUID victim) {
        long total = 0L;
        ArrayList<UUID> posters = new ArrayList<UUID>();
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("SELECT id, amount, poster_uuid FROM bounties WHERE target_uuid = ?");){
            ps.setString(1, victim.toString());
            ResultSet rs = ps.executeQuery();
            ArrayList<Integer> ids = new ArrayList<Integer>();
            while (rs.next()) {
                total += rs.getLong("amount");
                ids.add(rs.getInt("id"));
                posters.add(UUID.fromString(rs.getString("poster_uuid")));
            }
            for (Integer id : ids) {
                try {
                    PreparedStatement del = this.plugin.getDataManager().getConnection().prepareStatement("DELETE FROM bounties WHERE id = ?");
                    try {
                        del.setInt(1, id);
                        del.executeUpdate();
                    }
                    finally {
                        if (del == null) continue;
                        del.close();
                    }
                }
                catch (SQLException sQLException) {}
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return new BountyClaim(total, posters);
    }

    public List<Bounty> getActiveBounties() {
        ArrayList<Bounty> bounties = new ArrayList<Bounty>();
        try (Statement s = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM bounties ORDER BY amount DESC");){
            while (rs.next()) {
                bounties.add(new Bounty(rs.getInt("id"), UUID.fromString(rs.getString("poster_uuid")), UUID.fromString(rs.getString("target_uuid")), rs.getLong("amount"), rs.getLong("posted_at")));
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return bounties;
    }

    public static class Bounty {
        public final int id;
        public final UUID poster;
        public final UUID target;
        public final long amount;
        public final long postedAt;

        public Bounty(int id, UUID poster, UUID target, long amount, long postedAt) {
            this.id = id;
            this.poster = poster;
            this.target = target;
            this.amount = amount;
            this.postedAt = postedAt;
        }
    }

    public record BountyClaim(long total, List<UUID> posters) {
    }
}
