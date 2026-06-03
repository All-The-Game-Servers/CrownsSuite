package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public class SwordCommand implements TabExecutor {
    private static final List<String> GESTURES = List.of(
            "SNEAK_RIGHT_CLICK",
            "SNEAK_LEFT_CLICK",
            "SNEAK_SWAP_HAND",
            "SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK",
            "RIGHT_CLICK",
            "LEFT_CLICK"
    );
    private final CrownsSwordsPlugin plugin;

    public SwordCommand(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("skillbook")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open a skillbook.");
                return true;
            }
            this.plugin.gui().openSkillbook(player);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("focus")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can receive a training blade.");
                return true;
            }
            if (!player.hasPermission("crowns.swords.use")) {
                player.sendMessage("You do not have permission to use Crowns Swords.");
                return true;
            }
            if (!this.plugin.getConfig().getBoolean("swords.training-blade-enabled", true)) {
                player.sendMessage("Training blades are disabled.");
                return true;
            }
            player.getInventory().addItem(SwordItems.trainingBlade(this.plugin));
            player.sendMessage("You received a Training Blade.");
            return true;
        }
        if (root.equals("stamina")) {
            return this.showStamina(sender);
        }
        if (root.equals("progress")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players have Blade progress.");
                return true;
            }
            this.plugin.gui().openProgress(player);
            return true;
        }
        if (root.equals("playtest")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open playtest notes.");
                return true;
            }
            this.plugin.gui().openPlaytest(player);
            return true;
        }
        if (root.equals("skills")) {
            sender.sendMessage("Crowns Sword Arts");
            for (SwordSkill skill : this.plugin.skills().skills()) {
                sender.sendMessage("- " + skill.key() + " | " + skill.displayName() + " | " + skill.styleName() + " " + skill.rank().displayName() + " | stamina " + skill.staminaCost());
            }
            return true;
        }
        if (root.equals("styles")) {
            if (sender instanceof Player player) {
                for (String style : SwordProfileManager.STYLES.keySet()) {
                    sender.sendMessage(SwordProfileManager.STYLES.get(style) + ": " + this.plugin.profiles().masteryRank(player.getUniqueId(), style).displayName() + " (" + this.plugin.profiles().styleXp(player.getUniqueId(), style) + " XP)");
                }
            } else {
                sender.sendMessage("Styles: " + String.join(", ", SwordProfileManager.STYLES.keySet()));
            }
            return true;
        }
        if (root.equals("style")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /" + label + " style <flash|guard|phantom>");
                return true;
            }
            String style = args[1].toLowerCase(Locale.ROOT);
            if (!SwordProfileManager.STYLES.containsKey(style)) {
                sender.sendMessage("Unknown style. Valid: " + String.join(", ", SwordProfileManager.STYLES.keySet()));
                return true;
            }
            if (sender instanceof Player player) {
                this.plugin.gui().openStyle(player, style);
            } else {
                for (SwordSkill skill : this.plugin.skills().skills()) {
                    if (skill.styleKey().equals(style)) {
                        sender.sendMessage("- " + skill.key() + " | " + skill.displayName() + " | " + skill.rank().displayName());
                    }
                }
            }
            return true;
        }
        if (root.equals("bind")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can bind sword arts.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("Usage: /" + label + " bind <skill> <gesture>");
                return true;
            }
            String skillKey = args[1].toLowerCase(Locale.ROOT);
            SwordSkill skill = this.plugin.skills().skill(skillKey);
            if (skill == null) {
                sender.sendMessage("Unknown sword art. Use /" + label + " skills.");
                return true;
            }
            if (!this.plugin.profiles().isUnlocked(player.getUniqueId(), skill)) {
                sender.sendMessage("That sword art requires " + skill.styleName() + " " + skill.rank().displayName() + " mastery.");
                return true;
            }
            String gestureKey = SwordGestures.normalize(args[2]);
            if (!GESTURES.contains(gestureKey) || SwordGestures.fromKey(gestureKey) == null) {
                sender.sendMessage("Unknown gesture. Valid: " + String.join(", ", GESTURES));
                return true;
            }
            this.plugin.profiles().rebind(player.getUniqueId(), gestureKey, skillKey);
            this.plugin.profiles().saveAll();
            sender.sendMessage("Bound " + skill.displayName() + " to " + gestureKey.replace(">", " -> ") + ".");
            return true;
        }
        if (root.equals("reload")) {
            if (!sender.hasPermission("crowns.swords.admin")) {
                sender.sendMessage("You do not have permission to reload Crowns Swords.");
                return true;
            }
            this.plugin.reloadConfig();
            sender.sendMessage("Crowns Swords config reloaded. Restart is recommended after skill setting changes.");
            return true;
        }
        if (root.equals("admin")) {
            return this.admin(sender, label, args);
        }
        this.sendHelp(sender, label);
        return true;
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("crowns.swords.admin")) {
            sender.sendMessage("You do not have permission to manage Crowns Swords.");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("excalibur")) {
            return this.giveExcalibur(sender, label, args);
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /" + label + " admin <excalibur|xp|rank|stylexp|stylereset|reset|debug> [player] [style] [amount|rank|on|off]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage("That player is not online.");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("xp")) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /" + label + " admin xp <player> <amount>");
                return true;
            }
            int amount = this.parseInt(args[3], 0);
            this.plugin.profiles().addXp(target, amount, "admin grant");
            sender.sendMessage("Granted " + amount + " Blade XP to " + target.getName() + ".");
            return true;
        }
        if (action.equals("rank")) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /" + label + " admin rank <player> <1-5>");
                return true;
            }
            int rank = this.parseInt(args[3], 1);
            this.plugin.profiles().grantRank(target, rank);
            sender.sendMessage("Set " + target.getName() + " to Blade Rank " + rank + ".");
            return true;
        }
        if (action.equals("reset")) {
            this.plugin.profiles().resetProgress(target);
            sender.sendMessage("Reset Blade progress for " + target.getName() + ".");
            return true;
        }
        if (action.equals("stylexp")) {
            if (args.length < 5) {
                sender.sendMessage("Usage: /" + label + " admin stylexp <player> <style> <amount>");
                return true;
            }
            String style = args[3].toLowerCase(Locale.ROOT);
            if (!SwordProfileManager.STYLES.containsKey(style)) {
                sender.sendMessage("Unknown style. Valid: " + String.join(", ", SwordProfileManager.STYLES.keySet()));
                return true;
            }
            int amount = this.parseInt(args[4], 0);
            this.plugin.profiles().addStyleXp(target, style, amount, "admin grant");
            sender.sendMessage("Granted " + amount + " " + SwordProfileManager.STYLES.get(style) + " XP to " + target.getName() + ".");
            return true;
        }
        if (action.equals("stylereset")) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /" + label + " admin stylereset <player> <style>");
                return true;
            }
            String style = args[3].toLowerCase(Locale.ROOT);
            if (!SwordProfileManager.STYLES.containsKey(style)) {
                sender.sendMessage("Unknown style. Valid: " + String.join(", ", SwordProfileManager.STYLES.keySet()));
                return true;
            }
            this.plugin.profiles().resetStyle(target, style);
            sender.sendMessage("Reset " + SwordProfileManager.STYLES.get(style) + " mastery for " + target.getName() + ".");
            return true;
        }
        if (action.equals("debug")) {
            boolean enabled = args.length < 4 || args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
            if (CrownsAPI.getActionInputService() != null) {
                CrownsAPI.getActionInputService().setDebug(target.getUniqueId(), enabled);
            }
            sender.sendMessage("Swords input debug for " + target.getName() + ": " + enabled);
            return true;
        }
        sender.sendMessage("Unknown admin action.");
        return true;
    }

    private boolean giveExcalibur(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("That player is not online.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("Usage: /" + label + " admin excalibur <player>");
            return true;
        }
        target.getInventory().addItem(SwordItems.excalibur(this.plugin));
        if (target.equals(sender)) {
            target.sendMessage("You received Excalibur.");
        } else {
            target.sendMessage("You received Excalibur from " + sender.getName() + ".");
            sender.sendMessage("Gave Excalibur to " + target.getName() + ".");
        }
        return true;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean showStamina(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have stamina.");
            return true;
        }
        int stamina = CrownsAPI.getResourceMeterService() == null ? 0 : CrownsAPI.getResourceMeterService().get("swords:stamina", player.getUniqueId());
        int max = CrownsAPI.getResourceMeterService() == null ? 100 : CrownsAPI.getResourceMeterService().getMaximum("swords:stamina");
        player.sendMessage("Stamina: " + stamina + "/" + max);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("Crowns Swords");
        sender.sendMessage("/" + label + " - open your skillbook");
        sender.sendMessage("/" + label + " focus - receive a Training Blade");
        sender.sendMessage("/" + label + " stamina - show stamina");
        sender.sendMessage("/" + label + " progress - view Blade Rank");
        sender.sendMessage("/" + label + " styles - view style mastery");
        sender.sendMessage("/" + label + " style <style> - open a style page");
        sender.sendMessage("/" + label + " playtest - open playtest notes");
        sender.sendMessage("/" + label + " skills - list sword arts");
        sender.sendMessage("/" + label + " bind <skill> <gesture> - bind a learned sword art");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(List.of("skillbook", "focus", "stamina", "progress", "playtest", "skills", "styles", "style", "bind"));
            if (sender.hasPermission("crowns.swords.admin")) {
                roots.addAll(List.of("admin", "reload"));
            }
            return StringUtil.copyPartialMatches(args[0], roots, suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return StringUtil.copyPartialMatches(args[1], List.of("excalibur", "xp", "rank", "stylexp", "stylereset", "reset", "debug"), suggestions);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("excalibur")) {
            return StringUtil.copyPartialMatches(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("style")) {
            return StringUtil.copyPartialMatches(args[1], SwordProfileManager.STYLES.keySet(), suggestions);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("stylexp") || args[1].equalsIgnoreCase("stylereset"))) {
            return StringUtil.copyPartialMatches(args[3], SwordProfileManager.STYLES.keySet(), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bind")) {
            return StringUtil.copyPartialMatches(args[1], this.plugin.skills().skills().stream().map(SwordSkill::key).toList(), suggestions);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bind")) {
            return StringUtil.copyPartialMatches(args[2], GESTURES, suggestions);
        }
        return suggestions;
    }
}
