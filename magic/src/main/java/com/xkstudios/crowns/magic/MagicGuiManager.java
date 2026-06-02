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
    private static final int[] SPELL_SLOTS = {28, 29, 30, 31, 32, 33};
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

        int index = 0;
        for (String spellKey : profile.learnedSpells()) {
            if (index >= SPELL_SLOTS.length) {
                break;
            }
            MagicSpell spell = this.plugin.spells().spell(spellKey);
            if (spell == null) {
                continue;
            }
            inventory.setItem(SPELL_SLOTS[index++], this.info(Material.BOOK, spell.displayName(), NamedTextColor.LIGHT_PURPLE, List.of(
                    Component.text(spell.description(), NamedTextColor.GRAY),
                    Component.text("Mana: " + spell.manaCost(), NamedTextColor.AQUA),
                    Component.text("Cooldown: " + (spell.cooldownMillis() / 1000.0D) + "s", NamedTextColor.DARK_GRAY)
            ), spell.modelPath()));
        }
        inventory.setItem(48, this.actionButton(Material.AMETHYST_SHARD, "Get Focus", NamedTextColor.GREEN, List.of(
                Component.text("Receive a Starlit Focus.", NamedTextColor.GRAY)
        ), "focus", "lowlight/magic/focus"));
        inventory.setItem(49, this.actionButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Suite.", NamedTextColor.GRAY)
        ), "suite-home", "lowlight/suite/nav_back"));
        inventory.setItem(50, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void cycleBinding(Player player, String gestureKey) {
        MagicProfile profile = this.plugin.profiles().get(player.getUniqueId());
        List<String> learned = profile.learnedSpells().stream()
                .filter(key -> this.plugin.spells().spell(key) != null)
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
