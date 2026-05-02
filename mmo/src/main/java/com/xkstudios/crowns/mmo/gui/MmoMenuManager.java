package com.xkstudios.crowns.mmo.gui;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.mmo.MmoManager;
import com.xkstudios.crowns.mmo.MmoPerkNode;
import com.xkstudios.crowns.mmo.MmoSkill;
import com.xkstudios.crowns.mmo.floor.MmoFloor;
import com.xkstudios.crowns.mmo.item.MmoItemFactory;
import com.xkstudios.crowns.mmo.quest.MmoQuestManager;
import com.xkstudios.crowns.mmo.social.MmoGuildManager;
import com.xkstudios.crowns.mmo.social.MmoPartyManager;
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
        inventory.setItem(19, CrownsAPI.getSuiteGui().button(Material.PLAYER_HEAD, "Party", NamedTextColor.GREEN, List.of(
                Component.text("Temporary adventuring group and boss credit.", NamedTextColor.GRAY)
        ), "mmo:open:party"));
        inventory.setItem(21, CrownsAPI.getSuiteGui().button(Material.WHITE_BANNER, "Guild", NamedTextColor.GOLD, List.of(
                Component.text("Persistent guild identity and roster.", NamedTextColor.GRAY)
        ), "mmo:open:guild"));
        inventory.setItem(22, CrownsAPI.getSuiteGui().button(Material.ENDER_EYE, "Floor Gates", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Teleport to unlocked floor worlds.", NamedTextColor.GRAY),
                Component.text("Higher floors bring harder mobs and boss gates.", NamedTextColor.YELLOW)
        ), "mmo:open:floors"));
        inventory.setItem(28, CrownsAPI.getSuiteGui().button(Material.RAW_GOLD, "Floor Resources", NamedTextColor.GOLD, List.of(
                Component.text("See floor materials and where they drop.", NamedTextColor.GRAY)
        ), "mmo:open:resources"));
        inventory.setItem(30, CrownsAPI.getSuiteGui().button(Material.LEATHER_CHESTPLATE, "Adventurer Gear", NamedTextColor.AQUA, List.of(
                Component.text("Utility-first MMO gear for floor climbing.", NamedTextColor.GRAY)
        ), "mmo:open:gear"));
        inventory.setItem(32, CrownsAPI.getSuiteGui().button(Material.CRAFTING_TABLE, "Recipes", NamedTextColor.YELLOW, List.of(
                Component.text("Crafting and smithing paths for MMO gear.", NamedTextColor.GRAY)
        ), "mmo:open:recipes"));
        inventory.setItem(38, CrownsAPI.getSuiteGui().button(Material.BEACON, "Active Skills", NamedTextColor.GREEN, List.of(
                Component.text("Trigger Battle Surge, Ranger Focus, Bulwark, and Pathfinder.", NamedTextColor.GRAY)
        ), "mmo:open:actives"));
        inventory.setItem(39, CrownsAPI.getSuiteGui().button(Material.WRITABLE_BOOK, "Floor Quests", NamedTextColor.GOLD, List.of(
                Component.text("Light objectives that connect floors, villages, and rewards.", NamedTextColor.GRAY),
                Component.text(this.plugin.getQuestManager().getActiveViews(player).size() + " active quest(s).", NamedTextColor.YELLOW)
        ), "mmo:open:quests", "lowlight/mmo/quests"));
        inventory.setItem(42, CrownsAPI.getSuiteGui().button(Material.WRITABLE_BOOK, "Guide", NamedTextColor.WHITE, List.of(
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

    public void openParty(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-party", 54, Component.text("Adventuring Party", NamedTextColor.GREEN));
        MmoPartyManager.Party party = this.plugin.getPartyManager().getParty(player.getUniqueId());
        if (party == null) {
            inventory.setItem(13, CrownsAPI.getSuiteGui().info(Material.PLAYER_HEAD, "No Party", NamedTextColor.YELLOW, List.of(
                    Component.text("Use /cmmo party create to start one.", NamedTextColor.GRAY),
                    Component.text("Party members near a Floor Boss receive credit together.", NamedTextColor.GRAY)
            )));
        } else {
            inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.GOLDEN_HELMET, "Leader", NamedTextColor.GOLD, List.of(
                    Component.text(party.leaderName(), NamedTextColor.GRAY),
                    Component.text(this.plugin.getPartyManager().isLeader(player.getUniqueId()) ? "You lead this party." : "Follow the leader to boss arenas.", NamedTextColor.YELLOW)
            )));
            int slot = 12;
            for (String member : party.memberNames().values()) {
                inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.PLAYER_HEAD, member, NamedTextColor.GREEN, List.of(
                        Component.text("Party member", NamedTextColor.GRAY)
                )));
                slot = slot % 9 == 7 ? slot + 3 : slot + 1;
            }
            inventory.setItem(31, CrownsAPI.getSuiteGui().info(Material.DRAGON_HEAD, "Boss Credit", NamedTextColor.LIGHT_PURPLE, List.of(
                    Component.text("Party-started bosses credit nearby party members only.", NamedTextColor.GRAY),
                    Component.text("Stay inside the arena radius when the boss dies.", NamedTextColor.YELLOW)
            )));
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGuild(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-guild", 54, Component.text("Guild", NamedTextColor.GOLD));
        MmoGuildManager.GuildView guild = this.plugin.getGuildManager().getGuildForPlayer(player.getUniqueId());
        if (guild == null) {
            inventory.setItem(13, CrownsAPI.getSuiteGui().info(Material.WHITE_BANNER, "No Guild", NamedTextColor.YELLOW, List.of(
                    Component.text("Use /cmmo guild create <name> <tag> to found one.", NamedTextColor.GRAY),
                    Component.text("Guilds are identity and roster only in 1.5.0.", NamedTextColor.GRAY)
            )));
        } else {
            inventory.setItem(10, CrownsAPI.getSuiteGui().info(Material.WHITE_BANNER, "[" + guild.tag() + "] " + guild.name(), NamedTextColor.GOLD, List.of(
                    Component.text(guild.motd() == null ? "No MOTD set." : guild.motd(), NamedTextColor.GRAY),
                    Component.text("Identity shell: progression arrives later.", NamedTextColor.DARK_GRAY)
            )));
            int slot = 12;
            for (MmoGuildManager.GuildMember member : this.plugin.getGuildManager().getMembers(guild.guildId())) {
                inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.PLAYER_HEAD, member.playerName(), member.rank() == MmoGuildManager.GuildRank.OWNER ? NamedTextColor.GOLD : NamedTextColor.GREEN, List.of(
                        Component.text("Rank: " + member.rank().name(), NamedTextColor.GRAY)
                )));
                slot = slot % 9 == 7 ? slot + 3 : slot + 1;
            }
            inventory.setItem(31, CrownsAPI.getSuiteGui().info(Material.WRITABLE_BOOK, "Guild Commands", NamedTextColor.AQUA, List.of(
                    Component.text("/cmmo guild invite <player>", NamedTextColor.GRAY),
                    Component.text("/cmmo guild motd <text>", NamedTextColor.GRAY),
                    Component.text("/cmmo guild promote <player>", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
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
                Component.text("Floor Bosses unlock the next world for credited players.", NamedTextColor.GRAY)
        )));
        inventory.setItem(14, CrownsAPI.getSuiteGui().info(Material.COMPASS, "Discovery Journal", NamedTextColor.AQUA, List.of(
                Component.text("Biome discoveries: " + manager.countWorldProgress(player.getUniqueId(), "biome:"), NamedTextColor.GRAY),
                Component.text("New biomes feed Exploration XP and your world profile.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openFloors(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-floors", 54, Component.text("Floor Gates", NamedTextColor.LIGHT_PURPLE));
        int slot = 10;
        for (MmoFloor floor : this.plugin.getFloorManager().getFloors()) {
            boolean unlocked = this.plugin.getFloorManager().hasUnlocked(player, floor.number());
            var bossLocation = this.plugin.getFloorManager().getBossLocation(floor.number());
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(unlocked ? "Unlocked for you." : this.plugin.getFloorManager().formatUnlockLine(player, floor.number()), unlocked ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(Component.text("World: " + floor.worldName(), NamedTextColor.GRAY));
            lore.add(Component.text("Difficulty tier: " + String.format("%.2f", floor.difficulty()), NamedTextColor.YELLOW));
            lore.add(Component.text("Resource tier: " + floor.resourceTier(), NamedTextColor.GOLD));
            lore.add(Component.text("Boss: " + floor.bossName(), NamedTextColor.LIGHT_PURPLE));
            lore.add(Component.text(bossLocation == null ? "Boss arena: choosing on first load" : "Boss arena: X " + bossLocation.getBlockX() + ", Z " + bossLocation.getBlockZ(), NamedTextColor.GRAY));
            lore.add(Component.text(unlocked ? "Click to teleport." : "Clear the required boss to unlock.", unlocked ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
            inventory.setItem(slot, CrownsAPI.getSuiteGui().button(unlocked ? Material.ENDER_EYE : Material.ENDER_PEARL, "Floor " + floor.number(), unlocked ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY, lore, "mmo:floor:" + floor.number()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openResources(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-resources", 54, Component.text("Floor Resources", NamedTextColor.GOLD));
        int slot = 10;
        for (MmoFloor floor : this.plugin.getFloorManager().getFloors()) {
            if (floor.number() > 3) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Resource tier " + floor.resourceTier(), NamedTextColor.YELLOW));
            for (MmoItemFactory.ResourceDrop drop : this.plugin.getItemFactory().getResourceDrops(floor.number()).stream().limit(5).toList()) {
                lore.add(Component.text(drop.source() + ": " + drop.itemKey() + " (" + Math.round(drop.chance() * 100.0D) + "%)", NamedTextColor.GRAY));
            }
            inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.RAW_GOLD, "Floor " + floor.number() + " Resources", NamedTextColor.GOLD, lore));
            slot += 2;
        }
        slot = 28;
        for (MmoItemFactory.MmoItemDefinition item : this.plugin.getItemFactory().getItems().stream().filter(definition -> definition.category().equals("floor_material")).limit(7).toList()) {
            inventory.setItem(slot, this.plugin.getItemFactory().createItem(item.key(), 1));
            slot++;
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openGear(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-gear", 54, Component.text("Adventurer Gear", NamedTextColor.AQUA));
        int slot = 10;
        for (MmoItemFactory.MmoItemDefinition item : this.plugin.getItemFactory().getItemsByCategory("adventurer_gear")) {
            inventory.setItem(slot, this.plugin.getItemFactory().createItem(item.key(), 1));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(37, CrownsAPI.getSuiteGui().info(Material.SHIELD, "Utility First", NamedTextColor.GREEN, List.of(
                Component.text("1.4.0 gear supports exploration, gathering, and survival.", NamedTextColor.GRAY),
                Component.text("It avoids major raw damage power creep.", NamedTextColor.GRAY)
        )));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openRecipes(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-recipes", 54, Component.text("MMO Recipes", NamedTextColor.YELLOW));
        int slot = 10;
        for (MmoItemFactory.CraftRecipe recipe : this.plugin.getItemFactory().getCraftRecipes()) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Crafting Table", NamedTextColor.GOLD));
            lore.add(Component.text("MMO: " + String.join(", ", recipe.mmoIngredients()), NamedTextColor.GRAY));
            lore.add(Component.text("Vanilla: " + recipe.vanillaIngredients(), NamedTextColor.GRAY));
            inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.CRAFTING_TABLE, recipe.resultKey(), NamedTextColor.YELLOW, lore));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        for (MmoItemFactory.SmithingRecipe recipe : this.plugin.getItemFactory().getSmithingRecipes()) {
            List<Component> lore = List.of(
                    Component.text("Smithing Table", NamedTextColor.GOLD),
                    Component.text("Template: " + recipe.templateKey(), NamedTextColor.GRAY),
                    Component.text("Base: " + recipe.baseMaterial().name(), NamedTextColor.GRAY),
                    Component.text("Addition: " + recipe.additionKey(), NamedTextColor.GRAY)
            );
            inventory.setItem(slot, CrownsAPI.getSuiteGui().info(Material.SMITHING_TABLE, recipe.resultKey(), NamedTextColor.AQUA, lore));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
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

    public void openQuests(Player player) {
        Inventory inventory = CrownsMenuHolder.create("mmo-quests", 54, Component.text("Quest Board", NamedTextColor.GOLD));
        MmoQuestManager quests = this.plugin.getQuestManager();
        inventory.setItem(10, CrownsAPI.getSuiteGui().button(Material.WRITABLE_BOOK, "Available Quests", NamedTextColor.GOLD, List.of(
                Component.text(quests.getAvailableViews(player).size() + " quest(s) waiting to start.", NamedTextColor.GRAY),
                Component.text("Good place to begin if you are new.", NamedTextColor.YELLOW)
        ), "mmo:open:available-quests", "lowlight/mmo/quest"));
        inventory.setItem(12, CrownsAPI.getSuiteGui().button(Material.CLOCK, "Active Quests", NamedTextColor.AQUA, List.of(
                Component.text(quests.getActiveViews(player).size() + " quest(s) in progress.", NamedTextColor.GRAY)
        ), "mmo:open:active-quests", "lowlight/mmo/quest_active"));
        inventory.setItem(14, CrownsAPI.getSuiteGui().button(Material.MAP, "Completed Quests", NamedTextColor.GREEN, List.of(
                Component.text(quests.getCompletedViews(player).size() + " quest(s) completed.", NamedTextColor.GRAY)
        ), "mmo:open:completed-quests", "lowlight/mmo/quest_complete"));
        inventory.setItem(16, CrownsAPI.getSuiteGui().button(Material.GRASS_BLOCK, "Floor 1 Quests", NamedTextColor.YELLOW, List.of(
                Component.text("Starter-floor objectives and onboarding.", NamedTextColor.GRAY)
        ), "mmo:open:floor-quests:1", "lowlight/mmo/floors"));
        inventory.setItem(22, CrownsAPI.getSuiteGui().button(Material.COMPASS, "First Haven Path", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("The guided Floor 1 story path.", NamedTextColor.GRAY),
                Component.text("Villages, roads, camps, materials, and the First Gate.", NamedTextColor.YELLOW)
        ), "mmo:open:first-haven-path", "lowlight/mmo/quests"));
        inventory.setItem(30, CrownsAPI.getSuiteGui().info(Material.GRASS_BLOCK, "CrownsTerrain", CrownsAPI.getTerrain() == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN, List.of(
                Component.text(CrownsAPI.getTerrain() == null ? "Optional terrain provider is missing." : "Terrain POI quests can use generated locations.", NamedTextColor.GRAY),
                Component.text("MMO still works without it.", NamedTextColor.DARK_GRAY)
        ), "lowlight/suite/terrain"));
        inventory.setItem(32, CrownsAPI.getSuiteGui().info(Material.GOLD_INGOT, "CrownsEconomy", CrownsAPI.getEconomy() == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN, List.of(
                Component.text(CrownsAPI.getEconomy() == null ? "Crowns payouts will be skipped." : "Crowns quest rewards are enabled.", NamedTextColor.GRAY),
                Component.text("MMO XP rewards always work.", NamedTextColor.DARK_GRAY)
        ), "lowlight/suite/economy"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:hub"));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openActiveQuests(Player player) {
        this.openQuestList(player, "Active Quests", this.plugin.getQuestManager().getActiveViews(player), "mmo:open:quests");
    }

    public void openAvailableQuests(Player player) {
        this.openQuestList(player, "Available Quests", this.plugin.getQuestManager().getAvailableViews(player), "mmo:open:quests");
    }

    public void openCompletedQuests(Player player) {
        this.openQuestList(player, "Completed Quests", this.plugin.getQuestManager().getCompletedViews(player), "mmo:open:quests");
    }

    public void openFloorQuests(Player player, int floor) {
        this.openQuestList(player, "Floor " + floor + " Quests", this.plugin.getQuestManager().getViewsForFloor(player, floor), "mmo:open:quests");
    }

    public void openFirstHavenPath(Player player) {
        this.openQuestList(player, "First Haven Path", this.plugin.getQuestManager().getStoryPathViews(player), "mmo:open:quests");
    }

    public void openQuestDetail(Player player, String questKey) {
        MmoQuestManager.QuestDefinition quest = this.plugin.getQuestManager().getQuest(questKey);
        if (quest == null) {
            this.openQuests(player);
            return;
        }
        MmoQuestManager.QuestProgress progress = this.plugin.getQuestManager().getProgress(player.getUniqueId(), player.getName(), quest.key());
        Inventory inventory = CrownsMenuHolder.create("mmo-quest-" + quest.key(), 54, Component.text(quest.title(), NamedTextColor.GOLD));
        inventory.setItem(13, CrownsAPI.getSuiteGui().info(Material.WRITABLE_BOOK, quest.title(), progress.isCompleted() ? NamedTextColor.GREEN : NamedTextColor.GOLD, List.of(
                Component.text(quest.description(), NamedTextColor.GRAY),
                Component.text(quest.objectiveLine(), NamedTextColor.AQUA),
                Component.text("Hint: " + this.plugin.getQuestManager().destinationHint(player, quest), NamedTextColor.GRAY),
                Component.text("Progress: " + Math.min(progress.progress(), quest.amount()) + "/" + quest.amount(), progress.isCompleted() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
        ), "lowlight/mmo/quest_detail"));
        inventory.setItem(29, CrownsAPI.getSuiteGui().info(Material.EXPERIENCE_BOTTLE, "Rewards", NamedTextColor.AQUA, List.of(
                Component.text(this.plugin.getQuestManager().rewardLine(quest), NamedTextColor.YELLOW),
                Component.text(this.plugin.getQuestManager().providerLine(quest), NamedTextColor.GRAY)
        ), "lowlight/mmo/quest_reward"));
        if ("turnin".equals(quest.objectiveType()) && !progress.isCompleted()) {
            inventory.setItem(33, CrownsAPI.getSuiteGui().button(Material.CHEST, "Turn In Items", NamedTextColor.GREEN, List.of(
                    Component.text("Submit " + quest.amount() + "x " + quest.subject() + ".", NamedTextColor.GRAY)
            ), "mmo:quest:turnin:" + quest.key(), "lowlight/mmo/quest_turnin"));
        }
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "mmo:open:quests"));
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

    private void openQuestList(Player player, String title, List<MmoQuestManager.QuestView> quests, String backAction) {
        Inventory inventory = CrownsMenuHolder.create("mmo-quests", 54, Component.text(title, NamedTextColor.GOLD));
        int slot = 10;
        for (MmoQuestManager.QuestView view : quests) {
            MmoQuestManager.QuestDefinition quest = view.quest();
            MmoQuestManager.QuestProgress progress = view.progress();
            inventory.setItem(slot, CrownsAPI.getSuiteGui().button(progress.isCompleted() ? Material.MAP : Material.WRITABLE_BOOK,
                    quest.title(),
                    progress.isCompleted() ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                    List.of(
                            Component.text(quest.description(), NamedTextColor.GRAY),
                            Component.text(quest.objectiveLine(), NamedTextColor.AQUA),
                            Component.text("Hint: " + this.plugin.getQuestManager().destinationHint(player, quest), NamedTextColor.DARK_GRAY),
                            Component.text("Progress: " + Math.min(progress.progress(), quest.amount()) + "/" + quest.amount(), progress.isCompleted() ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                            Component.text("Click for details.", NamedTextColor.DARK_GRAY)
                    ),
                    "mmo:quest:" + quest.key(),
                    progress.isCompleted() ? "lowlight/mmo/quest_complete" : "lowlight/mmo/quest"));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
            if (slot >= 44) {
                break;
            }
        }
        if (quests.isEmpty()) {
            inventory.setItem(22, CrownsAPI.getSuiteGui().info(Material.BARRIER, "No Quests", NamedTextColor.GRAY, List.of(
                    Component.text("No quest entries are available in this view.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(45, CrownsAPI.getSuiteGui().button(Material.WRITABLE_BOOK, "Quest Board", NamedTextColor.GOLD, List.of(), "mmo:open:quests", "lowlight/mmo/quests"));
        inventory.setItem(47, CrownsAPI.getSuiteGui().button(Material.CLOCK, "Active Only", NamedTextColor.AQUA, List.of(), "mmo:open:active-quests", "lowlight/mmo/quest_active"));
        inventory.setItem(51, CrownsAPI.getSuiteGui().button(Material.MAP, "Completed", NamedTextColor.GREEN, List.of(), "mmo:open:completed-quests", "lowlight/mmo/quest_complete"));
        inventory.setItem(49, CrownsAPI.getSuiteGui().button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), backAction));
        CrownsAPI.getSuiteGui().fillBorder(inventory);
        player.openInventory(inventory);
    }
}
