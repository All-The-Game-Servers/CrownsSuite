package com.xkstudios.crowns.command;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.drugs.DrugProduct;
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

public class DrugCommand implements CommandExecutor, TabCompleter {
    private final CrownsPlugin plugin;

    public DrugCommand(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("business")) {
            this.plugin.getMenuManager().openHub(player);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "grow" -> this.grow(player, args);
            case "process" -> this.process(player, args);
            case "sell" -> this.sell(player, args);
            case "use" -> this.use(player);
            case "upgrades" -> this.upgrades(player);
            case "recipes" -> this.recipes(player);
            case "storage" -> this.storage(player);
            case "help" -> {
                this.sendHelp(player);
                yield true;
            }
            default -> {
                this.plugin.getMenuManager().openHub(player);
                yield true;
            }
        };
    }

    private boolean grow(Player player, String[] args) {
        DrugProduct product = this.productArg(args, 1, DrugProduct.MARIJUANA);
        this.msg(player, this.plugin.getDrugManager().grow(player, product), NamedTextColor.GREEN);
        return true;
    }

    private boolean process(Player player, String[] args) {
        DrugProduct product = this.productArg(args, 1, null);
        this.msg(player, product == null ? this.plugin.getDrugManager().processAll(player) : this.plugin.getDrugManager().process(player, product), NamedTextColor.GREEN);
        return true;
    }

    private boolean sell(Player player, String[] args) {
        DrugProduct product = this.productArg(args, 1, null);
        this.msg(player, product == null ? this.plugin.getDrugManager().sellAll(player) : this.plugin.getDrugManager().sell(player, product), NamedTextColor.GREEN);
        return true;
    }

    private boolean use(Player player) {
        this.plugin.getMenuManager().openUseMenu(player);
        return true;
    }

    private boolean upgrades(Player player) {
        this.plugin.getMenuManager().openUpgrades(player);
        return true;
    }

    private boolean recipes(Player player) {
        this.plugin.getMenuManager().openRecipesMenu(player);
        return true;
    }

    private boolean storage(Player player) {
        this.plugin.getMenuManager().openStorageMenu(player);
        return true;
    }

    private DrugProduct productArg(String[] args, int index, DrugProduct fallback) {
        if (args.length <= index) {
            return fallback;
        }
        DrugProduct parsed = DrugProduct.fromKey(args[index]);
        return parsed == null ? fallback : parsed;
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("=== CrownsDrugs ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (String line : List.of(
                "/drugs - Open the drugs hub",
                "/drugs grow <marijuana|cocaine|meth>",
                "/drugs process [product]",
                "/drugs sell [product]",
                "/drugs use",
                "/drugs recipes",
                "/drugs upgrades",
                "/drugs storage")) {
            player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private void msg(Player player, String message, NamedTextColor color) {
        player.sendMessage(Component.text(message, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("business", "grow", "process", "sell", "use", "recipes", "upgrades", "storage", "help").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("grow", "process", "sell").contains(args[0].toLowerCase(Locale.ROOT))) {
            return DrugProduct.keys().stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
