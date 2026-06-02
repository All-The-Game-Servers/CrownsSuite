package com.xkstudios.crowns.swords;

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

public class CrownsSwordsPlugin extends JavaPlugin {
    public static final String MODULE_KEY = "swords";
    private NamespacedKey trainingBladeKey;
    private NamespacedKey actionKey;
    private SwordProfileManager profileManager;
    private SwordSkillManager skillManager;
    private SwordGuiManager guiManager;
    private BukkitTask staminaTask;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.trainingBladeKey = new NamespacedKey(this, "training_blade");
        this.actionKey = new NamespacedKey(this, "swords_action");
        this.profileManager = new SwordProfileManager(this);
        this.profileManager.load();
        this.skillManager = new SwordSkillManager(this);
        this.guiManager = new SwordGuiManager(this);
        this.skillManager.registerSkills();

        Bukkit.getPluginManager().registerEvents(new SwordPlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SwordMenuListener(this), this);
        SwordCommand command = new SwordCommand(this);
        if (this.getCommand("swords") != null) {
            this.getCommand("swords").setExecutor(command);
            this.getCommand("swords").setTabCompleter(command);
        }

        CrownsAPI.registerModule(new ModuleDescriptor(
                MODULE_KEY,
                "CrownsSwords",
                "CrownsSwords",
                this.getDescription().getVersion(),
                "0.1.2",
                List.of("CrownsAPI"),
                List.of(),
                List.of("swords", "weapon-arts", "gesture-combat")
        ), this::health);
        CrownsAPI.registerSection(new SuiteSection(
                MODULE_KEY,
                "Swords",
                Material.DIAMOND_SWORD,
                "lowlight/swords/training_blade",
                "crowns.swords.use",
                player -> this.guiManager.openSkillbook(player),
                player -> "Weapon Arts"
        ));
        Bukkit.getOnlinePlayers().forEach(player -> this.profileManager.bindDefaults(player.getUniqueId()));
        this.startStaminaRegen();
    }

    @Override
    public void onDisable() {
        if (this.staminaTask != null) {
            this.staminaTask.cancel();
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

    private void startStaminaRegen() {
        int max = this.getConfig().getInt("swords.stamina.maximum", 100);
        int amount = this.getConfig().getInt("swords.stamina.regen-amount", 6);
        long interval = Math.max(20L, this.getConfig().getLong("swords.stamina.regen-interval-ticks", 35L));
        if (CrownsAPI.getResourceMeterService() != null) {
            CrownsAPI.getResourceMeterService().setMaximum("swords:stamina", max);
        }
        this.staminaTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (CrownsAPI.getResourceMeterService() == null) {
                return;
            }
            Bukkit.getOnlinePlayers().forEach(player -> CrownsAPI.getResourceMeterService()
                    .restore("swords:stamina", player.getUniqueId(), amount));
        }, interval, interval);
    }

    private ModuleHealth health() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                MODULE_KEY,
                "CrownsSwords",
                "CrownsSwords",
                this.getDescription().getVersion(),
                "0.1.2",
                List.of("CrownsAPI"),
                List.of(),
                List.of("swords", "weapon-arts", "gesture-combat")
        );
        if (CrownsAPI.getActionInputService() == null) {
            return ModuleHealth.of(descriptor, ServiceState.FAILED, "CrownsAPI action input service is offline.", List.of("Restart CrownsAPI before CrownsSwords."));
        }
        return ModuleHealth.of(descriptor, ServiceState.READY, "Swords online. Skills: " + this.skillManager.skills().size() + ".", List.of());
    }

    public NamespacedKey trainingBladeKey() {
        return this.trainingBladeKey;
    }

    public NamespacedKey actionKey() {
        return this.actionKey;
    }

    public SwordProfileManager profiles() {
        return this.profileManager;
    }

    public SwordSkillManager skills() {
        return this.skillManager;
    }

    public SwordGuiManager gui() {
        return this.guiManager;
    }
}
