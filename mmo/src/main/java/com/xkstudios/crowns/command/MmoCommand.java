package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public class MmoCommand implements TabExecutor {
    private final CrownsPlugin plugin;

    public MmoCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the CrownsMMO GUI.");
                return true;
            }
            this.plugin.getMenuManager().openHub(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            return this.handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use that CrownsMMO command.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "party" -> this.handleParty(player, args);
            case "guild" -> this.handleGuild(player, args);
            case "skills" -> this.plugin.getMenuManager().openSkills(player);
            case "professions" -> this.plugin.getMenuManager().openProfessions(player);
            case "combat" -> this.plugin.getMenuManager().openCombat(player);
            case "world" -> this.plugin.getMenuManager().openWorld(player);
            case "floors" -> this.plugin.getMenuManager().openFloors(player);
            case "quests" -> {
                if (args.length >= 2) {
                    switch (args[1].toLowerCase()) {
                        case "active" -> this.plugin.getMenuManager().openActiveQuests(player);
                        case "completed" -> this.plugin.getMenuManager().openCompletedQuests(player);
                        case "floor" -> {
                            if (args.length >= 3) {
                                int floor = this.parseFloor(args[2]);
                                if (floor > 0) {
                                    this.plugin.getMenuManager().openFloorQuests(player, floor);
                                } else {
                                    player.sendMessage("Usage: /" + label + " quests floor <number>");
                                }
                            } else {
                                player.sendMessage("Usage: /" + label + " quests floor <number>");
                            }
                        }
                        default -> this.plugin.getMenuManager().openQuests(player);
                    }
                } else {
                    this.plugin.getMenuManager().openQuests(player);
                }
            }
            case "quest" -> {
                if (args.length >= 2) {
                    this.plugin.getQuestManager().describe(player, args[1]);
                } else {
                    this.plugin.getMenuManager().openQuests(player);
                }
            }
            case "resources" -> this.plugin.getMenuManager().openResources(player);
            case "gear" -> this.plugin.getMenuManager().openGear(player);
            case "recipes" -> this.plugin.getMenuManager().openRecipes(player);
            case "floor" -> {
                if (args.length >= 2) {
                    try {
                        this.plugin.getFloorManager().teleportToFloor(player, Integer.parseInt(args[1]));
                    } catch (NumberFormatException exception) {
                        player.sendMessage("Usage: /" + label + " floor <number>");
                    }
                } else {
                    this.plugin.getMenuManager().openFloors(player);
                }
            }
            case "boss" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    this.plugin.getFloorManager().startBoss(player);
                } else {
                    this.plugin.getFloorManager().describeBoss(player);
                }
            }
            case "actives", "active" -> {
                if (args.length >= 2) {
                    this.plugin.getMmoManager().activate(player, args[1].toLowerCase());
                } else {
                    this.plugin.getMenuManager().openActives(player);
                }
            }
            default -> this.plugin.getMenuManager().openHub(player);
        }
        return true;
    }

    private void handleParty(Player player, String[] args) {
        if (args.length < 2) {
            this.plugin.getMenuManager().openParty(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> this.plugin.getPartyManager().create(player);
            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo party invite <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getPartyManager().invite(player, target);
            }
            case "accept" -> this.plugin.getPartyManager().accept(player);
            case "leave" -> this.plugin.getPartyManager().leave(player);
            case "kick" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo party kick <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getPartyManager().kick(player, target);
            }
            case "disband" -> this.plugin.getPartyManager().disband(player);
            case "info" -> this.plugin.getPartyManager().sendInfo(player);
            default -> this.plugin.getMenuManager().openParty(player);
        }
    }

    private void handleGuild(Player player, String[] args) {
        if (args.length < 2) {
            this.plugin.getMenuManager().openGuild(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> {
                if (args.length < 4) {
                    player.sendMessage("Usage: /cmmo guild create <name> <tag>");
                    return;
                }
                this.plugin.getGuildManager().create(player, args[2], args[3]);
            }
            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo guild invite <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getGuildManager().invite(player, target);
            }
            case "accept" -> this.plugin.getGuildManager().accept(player);
            case "leave" -> this.plugin.getGuildManager().leave(player);
            case "kick" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo guild kick <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getGuildManager().kick(player, target);
            }
            case "promote" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo guild promote <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getGuildManager().promote(player, target);
            }
            case "demote" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo guild demote <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("That player must be online.");
                    return;
                }
                this.plugin.getGuildManager().demote(player, target);
            }
            case "motd" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /cmmo guild motd <text>");
                    return;
                }
                this.plugin.getGuildManager().setMotd(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "info" -> this.plugin.getGuildManager().sendInfo(player);
            default -> this.plugin.getMenuManager().openGuild(player);
        }
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crowns.mmo.admin")) {
            sender.sendMessage("You do not have permission to use CrownsMMO admin commands.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /cmmo admin <floor|item|resources|quest> ...");
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "floor" -> this.handleAdminFloor(sender, args);
            case "item" -> this.handleAdminItem(sender, args);
            case "resources" -> this.handleAdminResources(sender, args);
            case "quest" -> this.handleAdminQuest(sender, args);
            default -> {
                sender.sendMessage("Usage: /cmmo admin <floor|item|resources|quest> ...");
                yield true;
            }
        };
    }

    private boolean handleAdminFloor(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("Usage: /cmmo admin floor <setspawn|setboss|unlock> ...");
            return true;
        }
        switch (args[2].toLowerCase()) {
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can set a floor spawn.");
                    return true;
                }
                this.plugin.getFloorManager().setSpawn(player, this.parseFloor(args[3]));
            }
            case "setboss" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can set a floor boss arena.");
                    return true;
                }
                this.plugin.getFloorManager().setBossLocation(player, this.parseFloor(args[3]));
            }
            case "unlock" -> {
                if (args.length < 5) {
                    sender.sendMessage("Usage: /cmmo admin floor unlock <player> <floor>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage("That player must be online for a manual floor unlock.");
                    return true;
                }
                if (this.plugin.getFloorManager().unlockFloor(target, this.parseFloor(args[4]), "admin")) {
                    sender.sendMessage("Unlocked Floor " + args[4] + " for " + target.getName() + ".");
                } else {
                    sender.sendMessage("That floor could not be unlocked, or " + target.getName() + " already has it.");
                }
            }
            default -> sender.sendMessage("Usage: /cmmo admin floor <setspawn|setboss|unlock> ...");
        }
        return true;
    }

    private boolean handleAdminItem(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[2].equalsIgnoreCase("give")) {
            sender.sendMessage("Usage: /cmmo admin item give <player> <item_key> [amount]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[3]);
        if (target == null) {
            sender.sendMessage("That player must be online.");
            return true;
        }
        int amount = args.length >= 6 ? Math.max(1, this.parseFloor(args[5])) : 1;
        var item = this.plugin.getItemFactory().createItem(args[4], amount);
        if (item == null) {
            sender.sendMessage("Unknown MMO item key: " + args[4]);
            return true;
        }
        target.getInventory().addItem(item);
        sender.sendMessage("Gave " + amount + "x " + args[4] + " to " + target.getName() + ".");
        return true;
    }

    private boolean handleAdminResources(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[2].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /cmmo admin resources reload");
            return true;
        }
        this.plugin.reloadConfig();
        this.plugin.getItemFactory().initialize();
        sender.sendMessage("CrownsMMO resource tables and recipes reloaded.");
        return true;
    }

    private boolean handleAdminQuest(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /cmmo admin quest <grant|reset|reload|debug|inspect> ...");
            return true;
        }
        switch (args[2].toLowerCase()) {
            case "reload" -> {
                this.plugin.reloadConfig();
                this.plugin.getQuestManager().reload();
                sender.sendMessage("CrownsMMO quests reloaded.");
            }
            case "grant" -> {
                if (args.length < 5) {
                    sender.sendMessage("Usage: /cmmo admin quest grant <player> <quest>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage("That player must be online.");
                    return true;
                }
                sender.sendMessage(this.plugin.getQuestManager().grant(target, args[4]) ? "Quest granted/completed." : "Could not grant that quest.");
            }
            case "reset" -> {
                if (args.length < 5) {
                    sender.sendMessage("Usage: /cmmo admin quest reset <player> <quest>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage("That player must be online.");
                    return true;
                }
                sender.sendMessage(this.plugin.getQuestManager().reset(target, args[4]) ? "Quest reset." : "Could not reset that quest.");
            }
            case "debug" -> {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /cmmo admin quest debug <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage("That player must be online.");
                    return true;
                }
                sender.sendMessage("Quest views: " + this.plugin.getQuestManager().getViews(target).size()
                        + ", active: " + this.plugin.getQuestManager().getActiveViews(target).size());
            }
            case "inspect" -> {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /cmmo admin quest inspect <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage("That player must be online.");
                    return true;
                }
                this.plugin.getQuestManager().sendInspect(sender, target);
            }
            default -> sender.sendMessage("Usage: /cmmo admin quest <grant|reset|reload|debug|inspect> ...");
        }
        return true;
    }

    private int parseFloor(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("party", "guild", "skills", "professions", "combat", "world", "floors", "floor", "quests", "quest", "boss", "resources", "gear", "recipes", "actives", "admin"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("quests")) {
            return StringUtil.copyPartialMatches(args[1], List.of("active", "completed", "floor"), suggestions);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("quests") && args[1].equalsIgnoreCase("floor")) {
            return StringUtil.copyPartialMatches(args[2], this.plugin.getFloorManager().getFloors().stream().map(floor -> String.valueOf(floor.number())).toList(), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("quest")) {
            return StringUtil.copyPartialMatches(args[1], this.plugin.getQuestManager().getQuests().stream().map(quest -> quest.key()).toList(), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            return StringUtil.copyPartialMatches(args[1], List.of("create", "invite", "accept", "leave", "kick", "disband", "info"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("guild")) {
            return StringUtil.copyPartialMatches(args[1], List.of("create", "invite", "accept", "leave", "kick", "promote", "demote", "motd", "info"), suggestions);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("party") || args[0].equalsIgnoreCase("guild"))
                && List.of("invite", "kick", "promote", "demote").contains(args[1].toLowerCase())) {
            return null;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("boss")) {
            return StringUtil.copyPartialMatches(args[1], List.of("start"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("floor")) {
            return StringUtil.copyPartialMatches(args[1], this.plugin.getFloorManager().getFloors().stream().map(floor -> String.valueOf(floor.number())).toList(), suggestions);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("actives") || args[0].equalsIgnoreCase("active"))) {
            return StringUtil.copyPartialMatches(args[1], List.of("battle-surge", "ranger-focus", "bulwark", "pathfinder"), suggestions);
        }
        if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("crowns.mmo.admin")) {
            if (args.length == 2) {
                return StringUtil.copyPartialMatches(args[1], List.of("floor", "item", "resources", "quest"), suggestions);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("floor")) {
                return StringUtil.copyPartialMatches(args[2], List.of("setspawn", "setboss", "unlock"), suggestions);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("item")) {
                return StringUtil.copyPartialMatches(args[2], List.of("give"), suggestions);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("resources")) {
                return StringUtil.copyPartialMatches(args[2], List.of("reload"), suggestions);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("quest")) {
                return StringUtil.copyPartialMatches(args[2], List.of("grant", "reset", "reload", "debug", "inspect"), suggestions);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("floor") && (args[2].equalsIgnoreCase("setspawn") || args[2].equalsIgnoreCase("setboss"))) {
                return StringUtil.copyPartialMatches(args[3], this.plugin.getFloorManager().getFloors().stream().map(floor -> String.valueOf(floor.number())).toList(), suggestions);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("floor") && args[2].equalsIgnoreCase("unlock")) {
                return null;
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("floor") && args[2].equalsIgnoreCase("unlock")) {
                return StringUtil.copyPartialMatches(args[4], this.plugin.getFloorManager().getFloors().stream().map(floor -> String.valueOf(floor.number())).toList(), suggestions);
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("item") && args[2].equalsIgnoreCase("give")) {
                return StringUtil.copyPartialMatches(args[4], this.plugin.getItemFactory().getItems().stream().map(item -> item.key()).toList(), suggestions);
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("quest") && (args[2].equalsIgnoreCase("grant") || args[2].equalsIgnoreCase("reset"))) {
                return StringUtil.copyPartialMatches(args[4], this.plugin.getQuestManager().getQuests().stream().map(quest -> quest.key()).toList(), suggestions);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("quest") && List.of("grant", "reset", "debug", "inspect").contains(args[2].toLowerCase())) {
                return null;
            }
        }
        return suggestions;
    }
}
