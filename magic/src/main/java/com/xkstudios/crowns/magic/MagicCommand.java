package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        if (root.equals("spells")) {
            sender.sendMessage("Crowns Magic Spells");
            for (MagicSpell spell : this.plugin.spells().spells()) {
                sender.sendMessage("- " + spell.key() + " | " + spell.displayName() + " | mana " + spell.manaCost());
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
            MagicProfile profile = this.plugin.profiles().get(player.getUniqueId());
            if (!profile.learnedSpells().contains(spellKey)) {
                sender.sendMessage("You have not learned that spell.");
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
        this.sendHelp(sender, label);
        return true;
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
        sender.sendMessage("/" + label + " spells - list spells");
        sender.sendMessage("/" + label + " bind <spell> <gesture> - bind a learned spell");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(List.of("spellbook", "focus", "mana", "spells", "bind"));
            if (sender.hasPermission("crowns.magic.admin")) {
                roots.add("reload");
            }
            return StringUtil.copyPartialMatches(args[0], roots, suggestions);
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
