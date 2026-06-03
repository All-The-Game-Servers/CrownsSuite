package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.ModuleDescriptor;
import com.xkstudios.crowns.api.ModuleHealth;
import com.xkstudios.crowns.api.ServiceState;
import com.xkstudios.crowns.api.SuiteSection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class CrownsMagicPlugin extends JavaPlugin {
    public static final String MODULE_KEY = "magic";
    private NamespacedKey focusKey;
    private NamespacedKey actionKey;
    private MagicProfileManager profileManager;
    private MagicSpellManager spellManager;
    private MagicGuiManager guiManager;
    private BukkitTask manaTask;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.focusKey = new NamespacedKey(this, "magic_focus");
        this.actionKey = new NamespacedKey(this, "magic_action");
        this.profileManager = new MagicProfileManager(this);
        this.profileManager.load();
        this.spellManager = new MagicSpellManager(this);
        this.guiManager = new MagicGuiManager(this);
        this.spellManager.registerSpells();

        Bukkit.getPluginManager().registerEvents(new MagicPlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MagicMenuListener(this), this);
        MagicCommand command = new MagicCommand(this);
        if (this.getCommand("magic") != null) {
            this.getCommand("magic").setExecutor(command);
            this.getCommand("magic").setTabCompleter(command);
        }
        if (this.getCommand("mana") != null) {
            this.getCommand("mana").setExecutor(command);
        }

        CrownsAPI.registerModule(new ModuleDescriptor(
                MODULE_KEY,
                "CrownsMagic",
                "CrownsMagic",
                this.getDescription().getVersion(),
                "0.1.4",
                List.of("CrownsAPI"),
                List.of(),
                List.of("magic", "abilities", "gesture-casting", "arcane-rank", "schools", "playtest-progression")
        ), this::health);
        CrownsAPI.registerSection(new SuiteSection(
                MODULE_KEY,
                "Magic",
                Material.AMETHYST_SHARD,
                "lowlight/magic/focus",
                "crowns.magic.use",
                player -> this.guiManager.openSpellbook(player),
                player -> "A World Born"
        ));
        Bukkit.getOnlinePlayers().forEach(player -> this.profileManager.bindDefaults(player.getUniqueId()));
        this.startManaRegen();
    }

    @Override
    public void onDisable() {
        if (this.manaTask != null) {
            this.manaTask.cancel();
        }
        if (CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().unregisterPlugin(MODULE_KEY);
        }
        CrownsAPI.unregisterSection(MODULE_KEY);
        CrownsAPI.unregisterModule(MODULE_KEY);
        if (this.profileManager != null) {
            this.profileManager.saveAll();
        }
    }

    private void startManaRegen() {
        int max = this.getConfig().getInt("magic.mana.maximum", 100);
        int amount = this.getConfig().getInt("magic.mana.regen-amount", 4);
        long interval = Math.max(20L, this.getConfig().getLong("magic.mana.regen-interval-ticks", 40L));
        if (CrownsAPI.getResourceMeterService() != null) {
            CrownsAPI.getResourceMeterService().setMaximum("magic:mana", max);
        }
        this.manaTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (CrownsAPI.getResourceMeterService() == null) {
                return;
            }
            Bukkit.getOnlinePlayers().forEach(player -> CrownsAPI.getResourceMeterService()
                    .restore("magic:mana", player.getUniqueId(), amount));
        }, interval, interval);
    }

    private ModuleHealth health() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                MODULE_KEY,
                "CrownsMagic",
                "CrownsMagic",
                this.getDescription().getVersion(),
                "0.1.4",
                List.of("CrownsAPI"),
                List.of(),
                List.of("magic", "abilities", "gesture-casting", "arcane-rank", "schools", "playtest-progression")
        );
        if (CrownsAPI.getActionInputService() == null) {
            return ModuleHealth.of(descriptor, ServiceState.FAILED, "CrownsAPI action input service is offline.", List.of("Restart CrownsAPI before CrownsMagic."));
        }
        return ModuleHealth.of(descriptor, ServiceState.READY, "Magic online. Spells: " + this.spellManager.spells().size() + ".", List.of());
    }

    public NamespacedKey focusKey() {
        return this.focusKey;
    }

    public NamespacedKey actionKey() {
        return this.actionKey;
    }

    public MagicProfileManager profiles() {
        return this.profileManager;
    }

    public MagicSpellManager spells() {
        return this.spellManager;
    }

    public MagicGuiManager gui() {
        return this.guiManager;
    }
}
