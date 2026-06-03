package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class MagicGuiManager {
    public static final String SPELLBOOK_KEY = "magic-spellbook";
    private static final String[] BINDING_KEYS = {
            "SNEAK_RIGHT_CLICK",
            "SNEAK_LEFT_CLICK",
            "SNEAK_SWAP_HAND",
            "SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK",
            "RIGHT_CLICK",
            "LEFT_CLICK"
    };
    private static final int[] BINDING_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int[] SPELL_SLOTS = {27, 28, 29, 30, 31, 32, 33, 34, 35};
    private final CrownsMagicPlugin plugin;

    public MagicGuiManager(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
    }

    public void openSpellbook(Player player) {
        MagicProfile profile = this.plugin.profiles().get(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create(SPELLBOOK_KEY, 54, Component.text("Crowns Magic", NamedTextColor.LIGHT_PURPLE));
        this.fillBorder(inventory);
        int mana = CrownsAPI.getResourceMeterService() == null ? 0 : CrownsAPI.getResourceMeterService().get("magic:mana", player.getUniqueId());
        int maxMana = CrownsAPI.getResourceMeterService() == null ? 100 : CrownsAPI.getResourceMeterService().getMaximum("magic:mana");
        inventory.setItem(4, this.info(Material.AMETHYST_SHARD, "A World Born", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Mana: " + mana + "/" + maxMana, NamedTextColor.AQUA),
                Component.text("Hold a Starlit Focus, sneak, then gesture.", NamedTextColor.GRAY),
                Component.text("Choose a school page to study v0.3 spells.", NamedTextColor.GRAY),
                Component.text("Click a binding to cycle through learned spells.", NamedTextColor.DARK_GRAY)
        ), "lowlight/magic/focus"));

        for (int i = 0; i < BINDING_KEYS.length; i++) {
            String gestureKey = BINDING_KEYS[i];
            String spellKey = profile.bindings().get(gestureKey);
            MagicSpell spell = spellKey == null ? null : this.plugin.spells().spell(spellKey);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Gesture: " + gestureKey.replace(">", " -> ").replace("_", " "), NamedTextColor.GRAY));
            lore.add(Component.text("Click to cycle learned spells.", NamedTextColor.YELLOW));
            if (spell != null) {
                lore.add(Component.text("Mana: " + spell.manaCost(), NamedTextColor.AQUA));
                lore.add(Component.text("Cooldown: " + (spell.cooldownMillis() / 1000.0D) + "s", NamedTextColor.DARK_GRAY));
            }
            inventory.setItem(BINDING_SLOTS[i], this.actionButton(
                    spell == null ? Material.GRAY_DYE : Material.ENCHANTED_BOOK,
                    spell == null ? "Unbound" : spell.displayName(),
                    NamedTextColor.AQUA,
                    lore,
                    "bind:" + gestureKey,
                    spell == null ? "lowlight/magic/spellbook" : spell.modelPath()
            ));
        }

        inventory.setItem(20, this.schoolButton(player, "elemental", Material.BLAZE_POWDER, "lowlight/magic/schools/elemental"));
        inventory.setItem(22, this.schoolButton(player, "restoration", Material.EMERALD, "lowlight/magic/schools/restoration"));
        inventory.setItem(24, this.schoolButton(player, "astral", Material.AMETHYST_SHARD, "lowlight/magic/schools/astral"));

        int index = 0;
        for (MagicSpell spell : this.plugin.spells().spells()) {
            if (index >= SPELL_SLOTS.length) {
                break;
            }
            if (!this.plugin.profiles().isUnlocked(player.getUniqueId(), spell)) {
                continue;
            }
            inventory.setItem(SPELL_SLOTS[index++], this.info(Material.BOOK, spell.displayName(), NamedTextColor.LIGHT_PURPLE, List.of(
                    Component.text(spell.description(), NamedTextColor.GRAY),
                    Component.text(spell.schoolName() + " - " + spell.rank().displayName(), NamedTextColor.YELLOW),
                    Component.text("Mana: " + spell.manaCost(), NamedTextColor.AQUA),
                    Component.text("Cooldown: " + (spell.cooldownMillis() / 1000.0D) + "s", NamedTextColor.DARK_GRAY)
            ), spell.modelPath()));
        }
        inventory.setItem(48, this.actionButton(Material.AMETHYST_SHARD, "Get Focus", NamedTextColor.GREEN, List.of(
                Component.text("Receive a Starlit Focus.", NamedTextColor.GRAY)
        ), "focus", "lowlight/magic/focus"));
        inventory.setItem(46, this.actionButton(Material.EXPERIENCE_BOTTLE, "Progress", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("View Arcane Rank, XP, and practice tasks.", NamedTextColor.GRAY)
        ), "progress", "lowlight/suite/status"));
        inventory.setItem(47, this.actionButton(Material.WRITABLE_BOOK, "Playtest Notes", NamedTextColor.YELLOW, List.of(
                Component.text("Quick guide for testing Magic v0.3.", NamedTextColor.GRAY)
        ), "playtest", "lowlight/magic/spellbook"));
        inventory.setItem(49, this.actionButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Suite.", NamedTextColor.GRAY)
        ), "suite-home", "lowlight/suite/nav_back"));
        inventory.setItem(50, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void openProgress(Player player) {
        MagicProfile profile = this.plugin.profiles().get(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create(SPELLBOOK_KEY, 54, Component.text("Magic Progress", NamedTextColor.LIGHT_PURPLE));
        this.fillBorder(inventory);
        int next = this.plugin.profiles().xpForNextRank(profile);
        inventory.setItem(4, this.info(Material.EXPERIENCE_BOTTLE, "Arcane Rank " + profile.rank(), NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("XP: " + profile.xp() + (next < 0 ? " / MAX" : " / " + next), NamedTextColor.AQUA),
                Component.text("Daily casts: " + profile.dailyCasts() + "/10", NamedTextColor.GRAY),
                Component.text("Daily hits: " + profile.dailyHits() + "/5", NamedTextColor.GRAY),
                Component.text("Daily support: " + profile.dailySupport() + "/3", NamedTextColor.GRAY)
        ), "lowlight/suite/status"));
        inventory.setItem(11, this.schoolButton(player, "elemental", Material.BLAZE_POWDER, "lowlight/magic/schools/elemental"));
        inventory.setItem(13, this.schoolButton(player, "restoration", Material.EMERALD, "lowlight/magic/schools/restoration"));
        inventory.setItem(15, this.schoolButton(player, "astral", Material.AMETHYST_SHARD, "lowlight/magic/schools/astral"));
        int slot = 19;
        for (MagicSpell spell : this.plugin.spells().spells()) {
            boolean unlocked = this.plugin.profiles().isUnlocked(player.getUniqueId(), spell);
            inventory.setItem(slot++, this.info(unlocked ? Material.ENCHANTED_BOOK : Material.BOOK, spell.displayName(), unlocked ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY, List.of(
                    Component.text(spell.description(), NamedTextColor.GRAY),
                    Component.text(spell.schoolName() + " - " + spell.rank().displayName(), NamedTextColor.YELLOW),
                    Component.text(unlocked ? "Unlocked" : "Needs " + spell.schoolName() + " " + spell.rank().displayName(), unlocked ? NamedTextColor.GREEN : NamedTextColor.RED)
            ), spell.modelPath()));
            if (slot >= 45) {
                break;
            }
        }
        inventory.setItem(48, this.actionButton(Material.ARROW, "Back to Spellbook", NamedTextColor.GRAY, List.of(), "spellbook", "lowlight/suite/nav_back"));
        inventory.setItem(49, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void openSchool(Player player, String schoolKey) {
        String normalized = schoolKey == null ? "astral" : schoolKey.toLowerCase(java.util.Locale.ROOT);
        String display = MagicProfileManager.SCHOOLS.getOrDefault(normalized, "Astral");
        Inventory inventory = CrownsMenuHolder.create(SPELLBOOK_KEY, 54, Component.text(display + " School", NamedTextColor.LIGHT_PURPLE));
        this.fillBorder(inventory);
        inventory.setItem(4, this.info(Material.ENCHANTED_BOOK, display + " Mastery", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("XP: " + this.plugin.profiles().schoolXp(player.getUniqueId(), normalized), NamedTextColor.AQUA),
                Component.text("Rank: " + this.plugin.profiles().masteryRank(player.getUniqueId(), normalized).displayName(), NamedTextColor.YELLOW),
                Component.text("Cast spells in this school to unlock deeper work.", NamedTextColor.GRAY)
        ), "lowlight/magic/schools/" + normalized));
        int slot = 10;
        for (MagicSpell spell : this.plugin.spells().spells()) {
            if (!spell.schoolKey().equals(normalized)) {
                continue;
            }
            boolean unlocked = this.plugin.profiles().isUnlocked(player.getUniqueId(), spell);
            inventory.setItem(slot++, this.info(unlocked ? Material.ENCHANTED_BOOK : Material.BOOK, spell.displayName(), unlocked ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY, List.of(
                    Component.text(spell.description(), NamedTextColor.GRAY),
                    Component.text("Tier: " + spell.rank().displayName(), NamedTextColor.YELLOW),
                    Component.text("Mana: " + spell.manaCost(), NamedTextColor.AQUA),
                    Component.text(unlocked ? "Unlocked" : "Needs " + spell.rank().displayName() + " mastery", unlocked ? NamedTextColor.GREEN : NamedTextColor.RED)
            ), spell.modelPath()));
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 45) {
                break;
            }
        }
        inventory.setItem(48, this.actionButton(Material.ARROW, "Back to Spellbook", NamedTextColor.GRAY, List.of(), "spellbook", "lowlight/suite/nav_back"));
        inventory.setItem(49, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void openPlaytest(Player player) {
        Inventory inventory = CrownsMenuHolder.create(SPELLBOOK_KEY, 54, Component.text("Magic Playtest Notes", NamedTextColor.LIGHT_PURPLE));
        this.fillBorder(inventory);
        inventory.setItem(4, this.info(Material.WRITABLE_BOOK, "Magic v0.3 Goals", NamedTextColor.YELLOW, List.of(
                Component.text("1. Try Elemental, Restoration, and Astral pages.", NamedTextColor.GRAY),
                Component.text("2. Earn school mastery from real casts and hits.", NamedTextColor.GRAY),
                Component.text("3. Unlock Apprentice/Adept spells in each school.", NamedTextColor.GRAY),
                Component.text("4. Report unclear gestures, fizzles, or weak visuals.", NamedTextColor.GRAY)
        ), "lowlight/magic/spellbook"));
        inventory.setItem(21, this.info(Material.AMETHYST_SHARD, "Gesture Identity", NamedTextColor.AQUA, List.of(
                Component.text("Hold a Starlit Focus.", NamedTextColor.GRAY),
                Component.text("Sneak + click/swap to cast bound spells.", NamedTextColor.GRAY),
                Component.text("Longer combos win over shorter bindings.", NamedTextColor.GRAY)
        ), "lowlight/magic/focus"));
        inventory.setItem(23, this.info(Material.END_ROD, "Visual Standard", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Look for readable star trails, bursts, and shimmer.", NamedTextColor.GRAY),
                Component.text("No PvP damage by default.", NamedTextColor.GRAY)
        ), "lowlight/magic/spells/starfall_spark"));
        inventory.setItem(48, this.actionButton(Material.ARROW, "Back to Spellbook", NamedTextColor.GRAY, List.of(), "spellbook", "lowlight/suite/nav_back"));
        inventory.setItem(49, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void cycleBinding(Player player, String gestureKey) {
        MagicProfile profile = this.plugin.profiles().get(player.getUniqueId());
        List<String> learned = this.plugin.spells().spells().stream()
                .filter(spell -> this.plugin.profiles().isUnlocked(player.getUniqueId(), spell))
                .map(MagicSpell::key)
                .toList();
        if (learned.isEmpty()) {
            player.sendMessage("You have not learned any spells.");
            return;
        }
        String current = profile.bindings().get(gestureKey);
        int next = current == null ? 0 : (learned.indexOf(current) + 1) % learned.size();
        if (next < 0) {
            next = 0;
        }
        String spell = learned.get(next);
        this.plugin.profiles().rebind(player.getUniqueId(), gestureKey, spell);
        this.plugin.profiles().saveAll();
        player.sendMessage("Bound " + gestureKey.replace(">", " -> ") + " to " + this.plugin.spells().spell(spell).displayName() + ".");
        this.openSpellbook(player);
    }

    public String readAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(this.plugin.actionKey(), PersistentDataType.STRING);
    }

    private ItemStack actionButton(Material material, String name, NamedTextColor color, List<Component> lore, String action, String modelPath) {
        ItemStack item = this.info(material, name, color, lore, modelPath);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(this.plugin.actionKey(), PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack schoolButton(Player player, String schoolKey, Material material, String modelPath) {
        String display = MagicProfileManager.SCHOOLS.getOrDefault(schoolKey, schoolKey);
        return this.actionButton(material, display + " School", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Mastery: " + this.plugin.profiles().masteryRank(player.getUniqueId(), schoolKey).displayName(), NamedTextColor.YELLOW),
                Component.text("XP: " + this.plugin.profiles().schoolXp(player.getUniqueId(), schoolKey), NamedTextColor.AQUA),
                Component.text("Click to view this school's spells.", NamedTextColor.GRAY)
        ), "school:" + schoolKey, modelPath);
    }

    private ItemStack info(Material material, String name, NamedTextColor color, List<Component> lore, String modelPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        PackModelHelper.apply(meta, modelPath);
        item.setItemMeta(meta);
        return item;
    }

    private void fillBorder(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            boolean border = slot < 9 || slot >= inventory.getSize() - 9 || slot % 9 == 0 || slot % 9 == 8;
            if (border && inventory.getItem(slot) == null) {
                inventory.setItem(slot, this.info(Material.PURPLE_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_PURPLE, List.of(), null));
            }
        }
    }
}
