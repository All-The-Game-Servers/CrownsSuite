/*
 * Decompiled with CFR 0.152.
 */
package com.xkstudios.crowns.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public class Currency {
    private static int PER_SHILLING = 100;
    private static int PER_CROWN = 10000;

    public static void reload(JavaPlugin plugin) {
        reload(plugin.getConfig().getConfigurationSection("currency"));
    }

    public static void reload(ConfigurationSection section) {
        if (section == null) {
            PER_SHILLING = 100;
            PER_CROWN = 10000;
            return;
        }
        PER_SHILLING = section.getInt("rates.pennies-per-shilling", 100);
        PER_CROWN = PER_SHILLING * section.getInt("rates.shillings-per-crown", 100);
    }

    public static String format(long pennies) {
        if (pennies <= 0L) {
            return "0 Pennies";
        }
        long crowns = pennies / (long)PER_CROWN;
        long remaining = pennies % (long)PER_CROWN;
        long shillings = remaining / (long)PER_SHILLING;
        long pence = remaining % (long)PER_SHILLING;
        StringBuilder sb = new StringBuilder();
        if (crowns > 0L) {
            sb.append(crowns).append("\u265b ");
        }
        if (shillings > 0L) {
            sb.append(shillings).append("\u269c ");
        }
        if (pence > 0L || sb.isEmpty()) {
            sb.append(pence).append("\u25cf");
        }
        return sb.toString().trim();
    }

    public static String formatShort(long pennies) {
        if (pennies >= (long)PER_CROWN) {
            return pennies / (long)PER_CROWN + "." + String.format("%02d", pennies % (long)PER_CROWN / (long)PER_SHILLING) + " Crowns";
        }
        if (pennies >= (long)PER_SHILLING) {
            return pennies / (long)PER_SHILLING + "." + String.format("%02d", pennies % (long)PER_SHILLING) + " Shillings";
        }
        return pennies + " Pennies";
    }

    public static long parse(String input) throws NumberFormatException {
        if (input == null || input.isEmpty()) {
            throw new NumberFormatException("Empty");
        }
        if ((input = input.toLowerCase().replace(",", "").replace(" ", "")).matches("^\\d+$")) {
            return Long.parseLong(input);
        }
        long total = 0L;
        StringBuilder num = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                num.append(c);
                continue;
            }
            if (num.isEmpty()) {
                throw new NumberFormatException("Bad format: " + input);
            }
            double val = Double.parseDouble(num.toString());
            switch (c) {
                case 'c': {
                    total += (long)(val * (double)PER_CROWN);
                    break;
                }
                case 's': {
                    total += (long)(val * (double)PER_SHILLING);
                    break;
                }
                case 'p': {
                    total += (long)val;
                    break;
                }
                default: {
                    throw new NumberFormatException("Unknown unit: " + c);
                }
            }
            num = new StringBuilder();
        }
        if (!num.isEmpty()) {
            total += Long.parseLong(num.toString());
        }
        if (total <= 0L) {
            throw new NumberFormatException("Must be positive");
        }
        return total;
    }

    public static int perShilling() {
        return PER_SHILLING;
    }

    public static int perCrown() {
        return PER_CROWN;
    }
}
