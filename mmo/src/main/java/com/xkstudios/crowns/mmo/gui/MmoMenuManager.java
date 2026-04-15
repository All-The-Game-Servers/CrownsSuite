package com.xkstudios.crowns.mmo.gui;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.mmo.MmoManager;
import com.xkstudios.crowns.mmo.MmoPerkNode;
import com.xkstudios.crowns.mmo.MmoSkill;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public class MmoMenuManager {
    private final CrownsPlugin plugin;

    public MmoMenuManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHub(Player player) {
        MmoManager manager = this.plugin.getMmoManager();
        Inventory inventory = CrownsMenuHolder.create("mmo-hub", 54, Component.text("CrownsMMO", NamedTextColor.AQUA));
        inventory.setItem(10, CrownsAPI.getSuiteGui().button(Material.EXPERIENCE_BOTTLE, "Skill Trees", NamedTextColor.AQUA, List.of(
                Component.text(manager.getProfileSummary(player.getUniqueId(), player.getName()), NamedTextColor.GRAY),
                Component.text("Open all MMO skill lines and perk trees.", NamedTextColor.GRAY)
        ), "mmo:open:skills"));
        inventory.setItem(12, CrownsAPI.getSuiteGui().button(Material.ANVIL, "Professions", NamedTextColor.GOLD, List.of(
                Component.text("Smithing, enchanting, brewing, and trading.", NamedTextColor.GRAY)
        ), "mmo:open:professions"));
        inventory.setItem(14, CrownsAPI.getSuiteGui().button(Material.DIAMOND_SWORD, "Combat", NamedTextColor.RED, List.of(
                Component.text("Swordsmanship, archery, defense, and actives.", NamedTextColor.GRAY)
        ), "mmo:open:combat"));
        inventory.setItem(16, CrownsAPI.getSuiteGui().button(Material.DRAGON_HEAD, "World Progress", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text(manager.getWorldProgressSummary(player.getUniqueId()), NamedTextColor.GRAY),
                Component.text(manager.getChapterLabel(player.getUniqueId(), player.getName()), NamedTextColor.YELLOW)
        ), "mmo:open:world"));
        inventory.setItem(30, CrownsAPI.getSuiteGui().button(Material.BEACON, "Active Skills", NamedTextColor.GREEN, List.of(
                Component.text("Trigger Battle Surge, Ranger Focus, Bulwark, and Pathfinder.", NamedTextColor.GRAY)
        ), "mmo:open:actives"));
        inventory.setItem(32, CrownsAPI.getSuiteGui().button(Material.WRITABLE_BOOK, "Guide", NamedTextColor.WHITE, List.of(
                Component.text("Read the Season 2 MMO direction and progression loop.", NamedTextColor.GRAY)
        ), "mmo:open:guide"));
        inventory.setItem(40, CrownsAPI.getSuiteGui().info(Material.ENCHANTED_BOOK, "MMO Profile", NamedTextColor.YELLOW, List.of(
                Component.text(manager.getTopSkillSummary(player.getUniqueId()), NamedTextColor.GRAY),
                Component.text("Available perk opportunities are spread across your skills.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().backToHomeButton());
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openSkills(Player player) {
        this.openSkillGroup(player, "All Skills", this.plugin.getMmoManager().getSkills(), "mmo:hub");
    }

    public void openProfessions(Player player) {
        this.openSkillGroup(player, "Professions", this.plugin.getMmoManager().getSkillsByFamily("Professions"), "mmo:hub");
    }

    public void openCombat(Player player) {
        this.openSkillGroup(player, "Combat Skills", this.plugin.getMmoManager().getSkillsByFamily("Combat"), "mmo:hub");
    }

    public void openWorld(Player player) {
        MmoManager manager = this.plugin.getMmoManager();
        Inventory inventory = CrownsMenuHolder.create("mmo-world", 54, Component.text("World Progress", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.DRAGON_HEAD, "Current Chapter", NamedTextColor.YELLOW, List.of(
                Component.text(manager.getChapterLabel(player.getUniqueId(), player.getName()), NamedTextColor.GRAY),
                Component.text("Driven by total MMO level and boss victories.", NamedTextColor.GRAY)
        )));
        inventory.setItem(12, CrownsAPI.getSuiteGui().info(Material.ENDER_DRAGON_SPAWN_EGG, "Boss Journal", NamedTextColor.RED, List.of(
                Component.text("Boss clears: " + manager.countWorldProgress(player.getUniqueId(), "boss:"), NamedTextColor.GRAY),
                Component.text("Dragon, Wither, Warden, and more count toward your arc.", NamedTextColor.GRAY)
        )));
        inventory.setItem(14, CrownsAPI.getSuiteGui().info(Material.COMPASS, "Discovery Journal", NamedTextColor.AQUA, List.of(
                Component.text("Biome discoveries: " + manager.countWorldProgress(player.getUniqueId(), "biome:"), NamedTextColor.GRAY),
                Component.text("New biomes feed Exploration XP and your world profile.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openActives(Player player) {
        MmoManager manager = this.plugin.getMmoManager();
        Inventory inventory = CrownsMenuHolder.create("mmo-actives", 54, Component.text("Active Skills", NamedTextColor.GREEN));
        int slot = 10;
        for (MmoManager.ActiveSkill active : manager.getActiveSkills()) {
            long cooldown = manager.getRemainingCooldown(player.getUniqueId(), active.key());
            inventory.setItem(slot, CrownsAPI.getSuiteGui().button(active.skill().icon(), active.displayName(), NamedTextColor.GREEN, List.of(
                    Component.text(active.description(), NamedTextColor.GRAY),
                    Component.text("Requires " + active.skill().displayName() + " " + active.requiredLevel(), NamedTextColor.GRAY),
                    Component.text(cooldown > 0L ? "Cooldown: " + (cooldown / 1000L) + "s" : "Ready", cooldown > 0L ? NamedTextColor.YELLOW : NamedTextColor.AQUA)
            ), "mmo:active:" + active.key()));
            slot += 2;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGuide(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-guide", 54, Component.text("CrownsMMO Guide", NamedTextColor.WHITE));
        inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.BOOK, "Season 2 MMO Core", NamedTextColor.AQUA, List.of(
                Component.text("CrownsMMO is the SAO-inspired progression layer for Lowlight SMP Season 2.", NamedTextColor.GRAY),
                Component.text("You build identity through combat, gathering, crafting, and exploration together.", NamedTextColor.GRAY)
        )));
        inventory.setItem(12, CrownsAPI.getSuiteGui().info(Material.IRON_SWORD, "Light Active Skills", NamedTextColor.RED, List.of(
                Component.text("CrownsMMO uses a few signature actives instead of overloading combat with hotbar skills.", NamedTextColor.GRAY)
        )));
        inventory.setItem(14, CrownsAPI.getSuiteGui().info(Material.ANVIL, "Anti-Spam Progression", NamedTextColor.GOLD, List.of(
                Component.text("Low-value repetition loses XP efficiency quickly.", NamedTextColor.GRAY),
                Component.text("Progression prefers variety, material quality, and real work.", NamedTextColor.GRAY)
        )));
        inventory.setItem(16, CrownsAPI.getSuiteGui().info(Material.DRAGON_HEAD, "World Arc", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Bosses and discoveries push your MMO chapter upward like a server-wide adventure arc.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openSkillDetail(Player player, MmoSkill skill) {
        MmoManager manager = this.plugin.getMmoManager();
        Inventory inventory = CrownsMenuHolder.create("mmo-skill-" + skill.key(), 54, Component.text(skill.displayName(), NamedTextColor.YELLOW));
        inventory.setItem(10, CrownsAPI.getSuiteGui().info(skill.icon(), skill.displayName(), NamedTextColor.YELLOW, List.of(
                Component.text(skill.family(), NamedTextColor.GRAY),
                Component.text(skill.description(), NamedTextColor.GRAY),
                Component.text(manager.formatLevelLine(player.getUniqueId(), player.getName(), skill), NamedTextColor.AQUA),
                Component.text(manager.formatPerkLine(player.getUniqueId(), player.getName(), skill), NamedTextColor.GRAY)
        )));
        int slot = 20;
        for (MmoPerkNode perk : manager.getPerks(skill)) {
            boolean unlocked = manager.hasPerk(player.getUniqueId(), perk.key());
            inventory.setItem(slot, CrownsAPI.getSuiteGui().button(skill.icon(), perk.displayName(), unlocked ? NamedTextColor.GREEN : NamedTextColor.GOLD, List.of(
                    Component.text("Requires level " + perk.requiredLevel(), NamedTextColor.GRAY),
                    Component.text(perk.description(), NamedTextColor.GRAY),
                    Component.text(unlocked ? "Unlocked" : "Click to unlock", unlocked ? NamedTextColor.GREEN : NamedTextColor.AQUA)
            ), "mmo:perk:" + skill.key() + ":" + perk.key()));
            slot += 2;
        }
        inventory.setItem(45, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back To Skills", NamedTextColor.GRAY, List.of(), "mmo:open:skills"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.BARRIER, "Back To Hub", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    private void openSkillGroup(Player player, String title, java.util.Collection<MmoSkill> skills, String backAction) {
        MmoManager manager = this.plugin.getMmoManager();
        Inventory inventory = CrownsMenuHolder.create("mmo-group-" + title.toLowerCase().replace(' ', '-'), 54, Component.text(title, NamedTextColor.AQUA));
        int slot = 10;
        for (MmoSkill skill : skills) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(skill.description(), NamedTextColor.GRAY));
            lore.add(Component.text(manager.formatLevelLine(player.getUniqueId(), player.getName(), skill), NamedTextColor.AQUA));
            lore.add(Component.text(manager.formatPerkLine(player.getUniqueId(), player.getName(), skill), NamedTextColor.GRAY));
            inventory.setItem(slot, CrownsAPI.getSuiteGui().button(skill.icon(), skill.displayName(), NamedTextColor.YELLOW, lore, "mmo:skill:" + skill.key()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), backAction));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }
}
