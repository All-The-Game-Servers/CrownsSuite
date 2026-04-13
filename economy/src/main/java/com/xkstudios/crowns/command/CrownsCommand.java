package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Legacy monolith command retained only so older references compile cleanly.
 * The rebuilt economy module routes live command usage through EconomyCommand.
 */
@Deprecated
public class CrownsCommand implements CommandExecutor, TabCompleter {
    public CrownsCommand(CrownsPlugin plugin) {
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("This legacy command shell is inactive. Use the rebuilt /ce commands.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
