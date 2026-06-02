package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class SwordGuiManager {
    public static final String SKILLBOOK_KEY = "swords-skillbook";
    private static final String[] BINDING_KEYS = {
            "SNEAK_RIGHT_CLICK",
            "SNEAK_LEFT_CLICK",
            "SNEAK_SWAP_HAND",
            "SNEAK_RIGHT_CLICK>SNEAK_LEFT_CLICK",
            "RIGHT_CLICK",
            "LEFT_CLICK"
    };
    private static final int[] BINDING_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int[] SKILL_SLOTS = {28, 29, 30, 31, 32, 33};
    private final CrownsSwordsPlugin plugin;

    public SwordGuiManager(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openSkillbook(Player player) {
        SwordProfile profile = this.plugin.profiles().get(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create(SKILLBOOK_KEY, 54, Component.text("Crowns Swords", NamedTextColor.AQUA));
        this.fillBorder(inventory);
        int stamina = CrownsAPI.getResourceMeterService() == null ? 0 : CrownsAPI.getResourceMeterService().get("swords:stamina", player.getUniqueId());
        int maxStamina = CrownsAPI.getResourceMeterService() == null ? 100 : CrownsAPI.getResourceMeterService().getMaximum("swords:stamina");
        inventory.setItem(4, this.info(Material.DIAMOND_SWORD, "Weapon Arts", NamedTextColor.AQUA, List.of(
                Component.text("Stamina: " + stamina + "/" + maxStamina, NamedTextColor.GREEN),
                Component.text("Hold a sword, sneak, then gesture.", NamedTextColor.GRAY),
                Component.text("Click a binding to cycle learned skills.", NamedTextColor.DARK_GRAY)
        ), "lowlight/swords/skillbook"));

        for (int i = 0; i < BINDING_KEYS.length; i++) {
            String gestureKey = BINDING_KEYS[i];
            String skillKey = profile.bindings().get(gestureKey);
            SwordSkill skill = skillKey == null ? null : this.plugin.skills().skill(skillKey);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Gesture: " + gestureKey.replace(">", " -> ").replace("_", " "), NamedTextColor.GRAY));
            lore.add(Component.text("Click to cycle learned skills.", NamedTextColor.YELLOW));
            if (skill != null) {
                lore.add(Component.text("Stamina: " + skill.staminaCost(), NamedTextColor.GREEN));
                lore.add(Component.text("Cooldown: " + (skill.cooldownMillis() / 1000.0D) + "s", NamedTextColor.DARK_GRAY));
            }
            inventory.setItem(BINDING_SLOTS[i], this.actionButton(
                    skill == null ? Material.GRAY_DYE : Material.IRON_SWORD,
                    skill == null ? "Unbound" : skill.displayName(),
                    NamedTextColor.AQUA,
                    lore,
                    "bind:" + gestureKey,
                    skill == null ? "lowlight/swords/skillbook" : skill.modelPath()
            ));
        }

        int index = 0;
        for (String skillKey : profile.learnedSkills()) {
            if (index >= SKILL_SLOTS.length) {
                break;
            }
            SwordSkill skill = this.plugin.skills().skill(skillKey);
            if (skill == null) {
                continue;
            }
            inventory.setItem(SKILL_SLOTS[index++], this.info(Material.BOOK, skill.displayName(), NamedTextColor.AQUA, List.of(
                    Component.text(skill.description(), NamedTextColor.GRAY),
                    Component.text("Stamina: " + skill.staminaCost(), NamedTextColor.GREEN),
                    Component.text("Cooldown: " + (skill.cooldownMillis() / 1000.0D) + "s", NamedTextColor.DARK_GRAY)
            ), skill.modelPath()));
        }
        inventory.setItem(48, this.actionButton(Material.IRON_SWORD, "Get Training Blade", NamedTextColor.GREEN, List.of(
                Component.text("Receive a test sword for weapon arts.", NamedTextColor.GRAY)
        ), "focus", "lowlight/swords/training_blade"));
        inventory.setItem(49, this.actionButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(
                Component.text("Return to Crowns Suite.", NamedTextColor.GRAY)
        ), "suite-home", "lowlight/suite/nav_back"));
        inventory.setItem(50, this.actionButton(Material.BARRIER, "Close", NamedTextColor.RED, List.of(), "close", "lowlight/suite/nav_close"));
        player.openInventory(inventory);
    }

    public void cycleBinding(Player player, String gestureKey) {
        SwordProfile profile = this.plugin.profiles().get(player.getUniqueId());
        List<String> learned = profile.learnedSkills().stream()
                .filter(key -> this.plugin.skills().skill(key) != null)
                .toList();
        if (learned.isEmpty()) {
            player.sendMessage("You have not learned any sword arts.");
            return;
        }
        String current = profile.bindings().get(gestureKey);
        int next = current == null ? 0 : (learned.indexOf(current) + 1) % learned.size();
        if (next < 0) {
            next = 0;
        }
        String skill = learned.get(next);
        this.plugin.profiles().rebind(player.getUniqueId(), gestureKey, skill);
        this.plugin.profiles().saveAll();
        player.sendMessage("Bound " + gestureKey.replace(">", " -> ") + " to " + this.plugin.skills().skill(skill).displayName() + ".");
        this.openSkillbook(player);
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
                inventory.setItem(slot, this.info(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_AQUA, List.of(), null));
            }
        }
    }
}
