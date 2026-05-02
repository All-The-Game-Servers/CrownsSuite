package com.xkstudios.crowns.mmo.social;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class MmoPartyManager {
    private final CrownsPlugin plugin;
    private final Map<UUID, Party> partiesByLeader = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToLeader = new ConcurrentHashMap<>();
    private final Map<UUID, PartyInvite> invitesByTarget = new ConcurrentHashMap<>();

    public MmoPartyManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean create(Player leader) {
        if (this.getParty(leader.getUniqueId()) != null) {
            leader.sendMessage(Component.text("You are already in a party.", NamedTextColor.YELLOW));
            return false;
        }
        Party party = new Party(leader.getUniqueId(), leader.getName());
        party.members().add(leader.getUniqueId());
        party.memberNames().put(leader.getUniqueId(), leader.getName());
        this.partiesByLeader.put(leader.getUniqueId(), party);
        this.playerToLeader.put(leader.getUniqueId(), leader.getUniqueId());
        leader.sendMessage(Component.text("Party created. Invite adventurers with /cmmo party invite <player>.", NamedTextColor.GREEN));
        return true;
    }

    public boolean invite(Player sender, Player target) {
        Party party = this.getParty(sender.getUniqueId());
        if (party == null) {
            sender.sendMessage(Component.text("Create a party first with /cmmo party create.", NamedTextColor.RED));
            return false;
        }
        if (!party.leaderId().equals(sender.getUniqueId())) {
            sender.sendMessage(Component.text("Only the party leader can invite players.", NamedTextColor.RED));
            return false;
        }
        if (this.getParty(target.getUniqueId()) != null) {
            sender.sendMessage(Component.text(target.getName() + " is already in a party.", NamedTextColor.YELLOW));
            return false;
        }
        int maxSize = this.plugin.getConfig().getInt("mmo.parties.max-size", 5);
        if (party.members().size() >= maxSize) {
            sender.sendMessage(Component.text("Your party is full.", NamedTextColor.RED));
            return false;
        }
        this.invitesByTarget.put(target.getUniqueId(), new PartyInvite(party.leaderId(), sender.getName(), System.currentTimeMillis()));
        sender.sendMessage(Component.text("Invited " + target.getName() + " to your party.", NamedTextColor.GREEN));
        target.sendMessage(Component.text(sender.getName() + " invited you to a CrownsMMO party. Use /cmmo party accept.", NamedTextColor.AQUA));
        return true;
    }

    public boolean accept(Player target) {
        PartyInvite invite = this.invitesByTarget.remove(target.getUniqueId());
        if (invite == null || this.isExpired(invite)) {
            target.sendMessage(Component.text("You do not have an active party invite.", NamedTextColor.RED));
            return false;
        }
        Party party = this.partiesByLeader.get(invite.leaderId());
        if (party == null) {
            target.sendMessage(Component.text("That party no longer exists.", NamedTextColor.RED));
            return false;
        }
        int maxSize = this.plugin.getConfig().getInt("mmo.parties.max-size", 5);
        if (party.members().size() >= maxSize) {
            target.sendMessage(Component.text("That party is now full.", NamedTextColor.RED));
            return false;
        }
        party.members().add(target.getUniqueId());
        party.memberNames().put(target.getUniqueId(), target.getName());
        this.playerToLeader.put(target.getUniqueId(), party.leaderId());
        this.broadcast(party, target.getName() + " joined the party.", NamedTextColor.GREEN);
        return true;
    }

    public boolean leave(Player player) {
        Party party = this.getParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(Component.text("You are not in a party.", NamedTextColor.YELLOW));
            return false;
        }
        if (party.leaderId().equals(player.getUniqueId())) {
            this.disband(player);
            return true;
        }
        party.members().remove(player.getUniqueId());
        party.memberNames().remove(player.getUniqueId());
        this.playerToLeader.remove(player.getUniqueId());
        this.broadcast(party, player.getName() + " left the party.", NamedTextColor.YELLOW);
        player.sendMessage(Component.text("You left the party.", NamedTextColor.YELLOW));
        return true;
    }

    public boolean kick(Player leader, Player target) {
        Party party = this.getParty(leader.getUniqueId());
        if (party == null || !party.leaderId().equals(leader.getUniqueId())) {
            leader.sendMessage(Component.text("Only a party leader can kick members.", NamedTextColor.RED));
            return false;
        }
        if (!party.members().contains(target.getUniqueId()) || target.getUniqueId().equals(leader.getUniqueId())) {
            leader.sendMessage(Component.text("That player is not a kickable party member.", NamedTextColor.RED));
            return false;
        }
        party.members().remove(target.getUniqueId());
        party.memberNames().remove(target.getUniqueId());
        this.playerToLeader.remove(target.getUniqueId());
        this.broadcast(party, target.getName() + " was removed from the party.", NamedTextColor.YELLOW);
        target.sendMessage(Component.text("You were removed from the party.", NamedTextColor.RED));
        return true;
    }

    public boolean disband(Player leader) {
        Party party = this.getParty(leader.getUniqueId());
        if (party == null || !party.leaderId().equals(leader.getUniqueId())) {
            leader.sendMessage(Component.text("Only a party leader can disband the party.", NamedTextColor.RED));
            return false;
        }
        this.broadcast(party, "The party was disbanded.", NamedTextColor.YELLOW);
        for (UUID memberId : party.members()) {
            this.playerToLeader.remove(memberId);
        }
        this.partiesByLeader.remove(party.leaderId());
        return true;
    }

    public void sendInfo(Player player) {
        Party party = this.getParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(Component.text("You are not in a party.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Party Leader: " + party.leaderName(), NamedTextColor.GOLD));
        player.sendMessage(Component.text("Members: " + String.join(", ", party.memberNames().values()), NamedTextColor.GRAY));
    }

    public Party getParty(UUID playerId) {
        UUID leaderId = this.playerToLeader.get(playerId);
        return leaderId == null ? null : this.partiesByLeader.get(leaderId);
    }

    public List<Player> resolveBossCredit(UUID starterId, Location center, double radius) {
        return this.resolveBossCredit(starterId, null, center, radius);
    }

    public List<Player> resolveBossCredit(UUID starterId, List<UUID> partySnapshot, Location center, double radius) {
        if (center == null || center.getWorld() == null) {
            return List.of();
        }
        double extra = this.plugin.getConfig().getDouble("mmo.parties.boss-credit-radius-extra", 0.0D);
        double radiusSquared = (radius + extra) * (radius + extra);
        if (partySnapshot != null && !partySnapshot.isEmpty()) {
            return this.nearbyMembers(partySnapshot, center, radiusSquared);
        }
        Party party = starterId == null ? null : this.getParty(starterId);
        if (party == null) {
            List<Player> nearby = new ArrayList<>();
            for (Player player : center.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                    nearby.add(player);
                }
            }
            return nearby;
        }
        return this.nearbyMembers(List.copyOf(party.members()), center, radiusSquared);
    }

    private List<Player> nearbyMembers(List<UUID> members, Location center, double radiusSquared) {
        List<Player> credited = new ArrayList<>();
        for (UUID memberId : members) {
            Player player = Bukkit.getPlayer(memberId);
            if (player == null || !player.getWorld().equals(center.getWorld())) {
                this.message(memberId, "You were too far from the Floor Boss to receive credit.", NamedTextColor.RED);
                continue;
            }
            if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                credited.add(player);
            } else {
                player.sendMessage(Component.text("You were too far from the Floor Boss to receive credit.", NamedTextColor.RED));
            }
        }
        return credited;
    }

    public List<UUID> members(UUID playerId) {
        Party party = this.getParty(playerId);
        return party == null ? List.of() : List.copyOf(party.members());
    }

    public boolean isLeader(UUID playerId) {
        Party party = this.getParty(playerId);
        return party != null && party.leaderId().equals(playerId);
    }

    private void broadcast(Party party, String message, NamedTextColor color) {
        for (UUID memberId : party.members()) {
            this.message(memberId, message, color);
        }
    }

    private void message(UUID playerId, String message, NamedTextColor color) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, color));
        }
    }

    private boolean isExpired(PartyInvite invite) {
        long timeoutMs = this.plugin.getConfig().getLong("mmo.parties.invite-timeout-seconds", 60L) * 1000L;
        return System.currentTimeMillis() - invite.invitedAt() > timeoutMs;
    }

    public record Party(UUID leaderId, String leaderName, Set<UUID> members, Map<UUID, String> memberNames) {
        private Party(UUID leaderId, String leaderName) {
            this(leaderId, leaderName, new LinkedHashSet<>(), new LinkedHashMap<>());
        }
    }

    private record PartyInvite(UUID leaderId, String leaderName, long invitedAt) {
    }
}
