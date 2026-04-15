package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.ArrayList;
import java.util.List;
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use CrownsMMO.");
            return true;
        }
        if (args.length == 0) {
            this.plugin.getMenuManager().openHub(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "skills" -> this.plugin.getMenuManager().openSkills(player);
            case "professions" -> this.plugin.getMenuManager().openProfessions(player);
            case "combat" -> this.plugin.getMenuManager().openCombat(player);
            case "world" -> this.plugin.getMenuManager().openWorld(player);
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("skills", "professions", "combat", "world", "actives"), suggestions);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("actives") || args[0].equalsIgnoreCase("active"))) {
            return StringUtil.copyPartialMatches(args[1], List.of("battle-surge", "ranger-focus", "bulwark", "pathfinder"), suggestions);
        }
        return suggestions;
    }
}
