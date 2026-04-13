package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.event.EventManager;
import com.xkstudios.crowns.event.EventManager.CollectorEntry;
import com.xkstudios.crowns.event.EventManager.MilestoneStatus;
import com.xkstudios.crowns.event.EventState;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class EventsCommand implements CommandExecutor, TabCompleter {
    private final CrownsPlugin plugin;

    public EventsCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            this.plugin.getMenuManager().openHub(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            this.sendStatus(player);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "progress" -> this.sendProgress(player);
            case "rewards" -> {
                this.plugin.getMenuManager().openRewards(player, this.plugin.getEventManager().getActiveEventKey());
                yield true;
            }
            case "guide" -> {
                this.plugin.getMenuManager().openGuide(player, this.plugin.getEventManager().getActiveEventKey());
                yield true;
            }
            case "turnin" -> this.turnIn(player, args);
            case "admin" -> this.admin(player, args);
            default -> {
                this.sendHelp(player);
                yield true;
            }
        };
    }

    private void sendStatus(Player player) {
        EventManager manager = this.plugin.getEventManager();
        player.sendMessage(Component.text("=== " + manager.getTitle() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("State: " + manager.getStatusLabel(), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(manager.getStatusDescription(), NamedTextColor.GRAY));
    }

    private boolean sendProgress(Player player) {
        EventManager manager = this.plugin.getEventManager();
        EventManager.EventProgress progress = manager.getProgress(player.getUniqueId(), player.getName());
        player.sendMessage(Component.text("=== " + manager.getMenuLabel() + " Progress ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Relics turned in: " + progress.relics(), NamedTextColor.GREEN));
        for (MilestoneStatus milestone : manager.getMilestones()) {
            player.sendMessage(Component.text(milestone.displayName() + ": " + milestone.progress() + "/" + milestone.target(),
                    milestone.completedAt() > 0L ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        List<CollectorEntry> leaders = manager.getTopCollectors(5);
        if (!leaders.isEmpty()) {
            player.sendMessage(Component.text("Top Collectors:", NamedTextColor.YELLOW));
            for (int i = 0; i < leaders.size(); i++) {
                CollectorEntry entry = leaders.get(i);
                player.sendMessage(Component.text((i + 1) + ". " + entry.name() + " - " + entry.relics(), NamedTextColor.GRAY));
            }
        }
        return true;
    }

    private boolean sendRewards(Player player) {
        player.sendMessage(Component.text("=== Reward Status ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        this.plugin.getEventManager().getRewardStatuses(player).forEach(status ->
                player.sendMessage(Component.text(status.display() + ": " + (status.claimed() ? "Claimed" : status.claimable() ? "Ready" : "Locked"),
                        status.claimed() ? NamedTextColor.GREEN : status.claimable() ? NamedTextColor.YELLOW : NamedTextColor.GRAY)));
        return true;
    }

    private boolean turnIn(Player player, String[] args) {
        int turnedIn = args.length >= 2 && args[1].equalsIgnoreCase("hand")
                ? this.plugin.getEventManager().turnInHand(player)
                : this.plugin.getEventManager().turnInInventory(player);
        player.sendMessage(Component.text("Turned in " + turnedIn + " relic point(s).", turnedIn > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean admin(Player player, String[] args) {
        if (!player.hasPermission("crowns.events.admin") && !player.isOp()) {
            player.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            this.sendHelp(player);
            return true;
        }
        EventManager manager = this.plugin.getEventManager();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> manager.forceStart();
            case "stop", "end" -> manager.endNow();
            case "pause" -> manager.togglePause();
            case "live" -> {
                if (args.length >= 3) {
                    String action = args[2].toLowerCase(Locale.ROOT);
                    if (action.equals("list")) {
                        List<String> liveMoments = manager.getLiveMomentSummaries();
                        if (liveMoments.isEmpty()) {
                            player.sendMessage(Component.text("No live moments are active right now.", NamedTextColor.GRAY));
                        } else {
                            player.sendMessage(Component.text("=== Live Moments ===", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                            for (String line : liveMoments) {
                                player.sendMessage(Component.text(line, NamedTextColor.GRAY));
                            }
                        }
                    } else if (action.equals("start") && args.length >= 4) {
                        String key = args[3].toLowerCase(Locale.ROOT);
                        boolean started = manager.triggerLiveMoment(key, this.labelForMoment(key), this.detailForMoment(key), 20L * 60L * 1000L, player.getName());
                        player.sendMessage(Component.text(started ? "Live moment started." : "Could not start that live moment.", started ? NamedTextColor.GREEN : NamedTextColor.RED));
                    } else if (action.equals("stop") && args.length >= 4) {
                        boolean stopped = manager.stopLiveMoment(args[3]);
                        player.sendMessage(Component.text(stopped ? "Live moment stopped." : "No live moment with that key is active.", stopped ? NamedTextColor.YELLOW : NamedTextColor.RED));
                    }
                }
            }
            case "schedule" -> {
                if (args.length >= 3) {
                    manager.scheduleFromInput(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                }
            }
            case "reset" -> {
                if (manager.getState() == EventState.ENDED) {
                    manager.scheduleFromInput(this.plugin.getConfig().getString("events." + manager.getActiveEventKey() + ".start-time", "2030-01-01 12:00"));
                }
            }
            default -> {
            }
        }
        this.sendStatus(player);
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("=== CrownsEvents ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (String line : List.of(
                "/events status",
                "/events progress",
                "/events rewards",
                "/events guide",
                "/events turnin <hand|all>",
                "/events admin schedule <yyyy-MM-dd HH:mm>",
                "/events admin start",
                "/events admin live list",
                "/events admin live start <dragon-ceremony|expedition|boss-rush>",
                "/events admin live stop <key>",
                "/events admin pause",
                "/events admin end")) {
            player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return this.filter(List.of("status", "progress", "rewards", "guide", "turnin", "admin"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("turnin")) {
            return this.filter(List.of("hand", "all"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return this.filter(List.of("schedule", "start", "pause", "end", "reset", "live"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("live")) {
            return this.filter(List.of("list", "start", "stop"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("live")) {
            return this.filter(List.of("dragon-ceremony", "expedition", "boss-rush"), args[3]);
        }
        return List.of();
    }

    private String labelForMoment(String key) {
        return switch (key) {
            case "dragon-ceremony" -> "Dragon Ceremony";
            case "boss-rush" -> "Void Boss Rush";
            case "expedition" -> "Outer Island Expedition";
            default -> "Live Moment";
        };
    }

    private String detailForMoment(String key) {
        return switch (key) {
            case "dragon-ceremony" -> "Gather at the central island for the Endfall kickoff.";
            case "boss-rush" -> "A combat-heavy live push has begun. Hunt voidbound elites across the outer islands.";
            case "expedition" -> "An exploration push has begun. Survey caches and landmarks are the focus right now.";
            default -> "A staff-run live moment is active.";
        };
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
