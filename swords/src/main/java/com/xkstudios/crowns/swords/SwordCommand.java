package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        if (root.equals("skills")) {
            sender.sendMessage("Crowns Sword Arts");
            for (SwordSkill skill : this.plugin.skills().skills()) {
                sender.sendMessage("- " + skill.key() + " | " + skill.displayName() + " | stamina " + skill.staminaCost());
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
            SwordProfile profile = this.plugin.profiles().get(player.getUniqueId());
            if (!profile.learnedSkills().contains(skillKey)) {
                sender.sendMessage("You have not learned that sword art.");
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
        this.sendHelp(sender, label);
        return true;
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
        sender.sendMessage("/" + label + " skills - list sword arts");
        sender.sendMessage("/" + label + " bind <skill> <gesture> - bind a learned sword art");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(List.of("skillbook", "focus", "stamina", "skills", "bind"));
            if (sender.hasPermission("crowns.swords.admin")) {
                roots.add("reload");
            }
            return StringUtil.copyPartialMatches(args[0], roots, suggestions);
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
