package com.xkstudios.crowns.magic;

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

public class MagicCommand implements TabExecutor {
    private static final List<String> GESTURES = List.of(
            "SNEAK_RIGHT_CLICK",
            "SNEAK_LEFT_CLICK",
            "SNEAK_SWAP_HAND",
            "SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK",
            "RIGHT_CLICK",
            "LEFT_CLICK"
    );
    private final CrownsMagicPlugin plugin;

    public MagicCommand(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("mana")) {
            return this.showMana(sender);
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("spellbook")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open a spellbook.");
                return true;
            }
            this.plugin.gui().openSpellbook(player);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("focus")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can receive a spell focus.");
                return true;
            }
            if (!player.hasPermission("crowns.magic.use")) {
                player.sendMessage("You do not have permission to use Crowns Magic.");
                return true;
            }
            player.getInventory().addItem(MagicItems.focus(this.plugin));
            player.sendMessage("You received a Starlit Focus.");
            return true;
        }
        if (root.equals("mana")) {
            return this.showMana(sender);
        }
        if (root.equals("progress")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players have Arcane progress.");
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
        if (root.equals("spells")) {
            sender.sendMessage("Crowns Magic Spells");
            for (MagicSpell spell : this.plugin.spells().spells()) {
                sender.sendMessage("- " + spell.key() + " | " + spell.displayName() + " | " + spell.schoolName() + " " + spell.rank().displayName() + " | mana " + spell.manaCost());
            }
            return true;
        }
        if (root.equals("schools")) {
            if (sender instanceof Player player) {
                for (String school : MagicProfileManager.SCHOOLS.keySet()) {
                    sender.sendMessage(MagicProfileManager.SCHOOLS.get(school) + ": " + this.plugin.profiles().masteryRank(player.getUniqueId(), school).displayName() + " (" + this.plugin.profiles().schoolXp(player.getUniqueId(), school) + " XP)");
                }
            } else {
                sender.sendMessage("Schools: " + String.join(", ", MagicProfileManager.SCHOOLS.keySet()));
            }
            return true;
        }
        if (root.equals("school")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /" + label + " school <elemental|restoration|astral>");
                return true;
            }
            String school = args[1].toLowerCase(Locale.ROOT);
            if (!MagicProfileManager.SCHOOLS.containsKey(school)) {
                sender.sendMessage("Unknown school. Valid: " + String.join(", ", MagicProfileManager.SCHOOLS.keySet()));
                return true;
            }
            if (sender instanceof Player player) {
                this.plugin.gui().openSchool(player, school);
            } else {
                for (MagicSpell spell : this.plugin.spells().spells()) {
                    if (spell.schoolKey().equals(school)) {
                        sender.sendMessage("- " + spell.key() + " | " + spell.displayName() + " | " + spell.rank().displayName());
                    }
                }
            }
            return true;
        }
        if (root.equals("bind")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can bind spell gestures.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("Usage: /" + label + " bind <spell> <gesture>");
                return true;
            }
            String spellKey = args[1].toLowerCase(Locale.ROOT);
            MagicSpell spell = this.plugin.spells().spell(spellKey);
            if (spell == null) {
                sender.sendMessage("Unknown spell. Use /" + label + " spells.");
                return true;
            }
            if (!this.plugin.profiles().isUnlocked(player.getUniqueId(), spell)) {
                sender.sendMessage("That spell requires " + spell.schoolName() + " " + spell.rank().displayName() + " mastery.");
                return true;
            }
            String gestureKey = MagicGestures.normalize(args[2]);
            if (!GESTURES.contains(gestureKey) || MagicGestures.fromKey(gestureKey) == null) {
                sender.sendMessage("Unknown gesture. Valid: " + String.join(", ", GESTURES));
                return true;
            }
            this.plugin.profiles().rebind(player.getUniqueId(), gestureKey, spellKey);
            this.plugin.profiles().saveAll();
            sender.sendMessage("Bound " + spell.displayName() + " to " + gestureKey.replace(">", " -> ") + ".");
            return true;
        }
        if (root.equals("reload")) {
            if (!sender.hasPermission("crowns.magic.admin")) {
                sender.sendMessage("You do not have permission to reload Crowns Magic.");
                return true;
            }
            this.plugin.reloadConfig();
            sender.sendMessage("Crowns Magic config reloaded. Restart is recommended after spell setting changes.");
            return true;
        }
        if (root.equals("admin")) {
            return this.admin(sender, label, args);
        }
        this.sendHelp(sender, label);
        return true;
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("crowns.magic.admin")) {
            sender.sendMessage("You do not have permission to manage Crowns Magic.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /" + label + " admin <xp|rank|schoolxp|schoolreset|reset|debug> <player> [school] [amount|rank|on|off]");
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
            sender.sendMessage("Granted " + amount + " Arcane XP to " + target.getName() + ".");
            return true;
        }
        if (action.equals("rank")) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /" + label + " admin rank <player> <1-5>");
                return true;
            }
            int rank = this.parseInt(args[3], 1);
            this.plugin.profiles().grantRank(target, rank);
            sender.sendMessage("Set " + target.getName() + " to Arcane Rank " + rank + ".");
            return true;
        }
        if (action.equals("reset")) {
            this.plugin.profiles().resetProgress(target);
            sender.sendMessage("Reset Arcane progress for " + target.getName() + ".");
            return true;
        }
        if (action.equals("schoolxp")) {
            if (args.length < 5) {
                sender.sendMessage("Usage: /" + label + " admin schoolxp <player> <school> <amount>");
                return true;
            }
            String school = args[3].toLowerCase(Locale.ROOT);
            if (!MagicProfileManager.SCHOOLS.containsKey(school)) {
                sender.sendMessage("Unknown school. Valid: " + String.join(", ", MagicProfileManager.SCHOOLS.keySet()));
                return true;
            }
            int amount = this.parseInt(args[4], 0);
            this.plugin.profiles().addSchoolXp(target, school, amount, "admin grant");
            sender.sendMessage("Granted " + amount + " " + MagicProfileManager.SCHOOLS.get(school) + " XP to " + target.getName() + ".");
            return true;
        }
        if (action.equals("schoolreset")) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /" + label + " admin schoolreset <player> <school>");
                return true;
            }
            String school = args[3].toLowerCase(Locale.ROOT);
            if (!MagicProfileManager.SCHOOLS.containsKey(school)) {
                sender.sendMessage("Unknown school. Valid: " + String.join(", ", MagicProfileManager.SCHOOLS.keySet()));
                return true;
            }
            this.plugin.profiles().resetSchool(target, school);
            sender.sendMessage("Reset " + MagicProfileManager.SCHOOLS.get(school) + " mastery for " + target.getName() + ".");
            return true;
        }
        if (action.equals("debug")) {
            boolean enabled = args.length < 4 || args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
            if (CrownsAPI.getActionInputService() != null) {
                CrownsAPI.getActionInputService().setDebug(target.getUniqueId(), enabled);
            }
            sender.sendMessage("Magic input debug for " + target.getName() + ": " + enabled);
            return true;
        }
        sender.sendMessage("Unknown admin action.");
        return true;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean showMana(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have mana.");
            return true;
        }
        int mana = CrownsAPI.getResourceMeterService() == null ? 0 : CrownsAPI.getResourceMeterService().get("magic:mana", player.getUniqueId());
        int max = CrownsAPI.getResourceMeterService() == null ? 100 : CrownsAPI.getResourceMeterService().getMaximum("magic:mana");
        player.sendMessage("Mana: " + mana + "/" + max);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("Crowns Magic");
        sender.sendMessage("/" + label + " - open your spellbook");
        sender.sendMessage("/" + label + " focus - receive a Starlit Focus");
        sender.sendMessage("/" + label + " mana - show mana");
        sender.sendMessage("/" + label + " progress - view Arcane Rank");
        sender.sendMessage("/" + label + " schools - view school mastery");
        sender.sendMessage("/" + label + " school <school> - open a school page");
        sender.sendMessage("/" + label + " playtest - open playtest notes");
        sender.sendMessage("/" + label + " spells - list spells");
        sender.sendMessage("/" + label + " bind <spell> <gesture> - bind a learned spell");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(List.of("spellbook", "focus", "mana", "progress", "playtest", "spells", "schools", "school", "bind"));
            if (sender.hasPermission("crowns.magic.admin")) {
                roots.addAll(List.of("admin", "reload"));
            }
            return StringUtil.copyPartialMatches(args[0], roots, suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return StringUtil.copyPartialMatches(args[1], List.of("xp", "rank", "schoolxp", "schoolreset", "reset", "debug"), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("school")) {
            return StringUtil.copyPartialMatches(args[1], MagicProfileManager.SCHOOLS.keySet(), suggestions);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("schoolxp") || args[1].equalsIgnoreCase("schoolreset"))) {
            return StringUtil.copyPartialMatches(args[3], MagicProfileManager.SCHOOLS.keySet(), suggestions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bind")) {
            return StringUtil.copyPartialMatches(args[1], this.plugin.spells().spells().stream().map(MagicSpell::key).toList(), suggestions);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bind")) {
            return StringUtil.copyPartialMatches(args[2], GESTURES, suggestions);
        }
        return suggestions;
    }
}
