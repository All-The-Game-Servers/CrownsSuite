package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.action.AbilityCastContext;
import com.xkstudios.crowns.api.action.AbilityCastResult;
import com.xkstudios.crowns.api.action.AbilityCategory;
import com.xkstudios.crowns.api.action.AbilityRegistration;
import com.xkstudios.crowns.api.action.AbilityType;
import com.xkstudios.crowns.api.action.HitSafety;
import com.xkstudios.crowns.api.action.TargetingHelper;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class SwordSkillManager {
    private final CrownsSwordsPlugin plugin;
    private final Map<String, SwordSkill> skills = new LinkedHashMap<>();
    private final Set<Material> allowedWeapons = new HashSet<>();

    public SwordSkillManager(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSkills() {
        this.reloadWeapons();
        this.add("linear", "Linear", "A quick forward thrust with a bright sword trail.", "lowlight/swords/skills/linear", AbilityCategory.STRIKE);
        this.add("horizontal_arc", "Horizontal Arc", "A sweeping side slash that catches nearby enemies.", "lowlight/swords/skills/horizontal_arc", AbilityCategory.AOE);
        this.add("starburst_step", "Starburst Step", "Dash forward and strike with a star-flash impact.", "lowlight/swords/skills/starburst_step", AbilityCategory.DASH);
        this.add("guard_breaker", "Guard Breaker", "A heavy focused cut that weakens one target.", "lowlight/swords/skills/guard_breaker", AbilityCategory.STRIKE);
        this.add("aegis_parry", "Aegis Parry", "Brace for impact and release a small defensive pulse.", "lowlight/swords/skills/aegis_parry", AbilityCategory.GUARD);
        this.add("whirling_edge", "Whirling Edge", "Spin a short-range ring of blade pressure.", "lowlight/swords/skills/whirling_edge", AbilityCategory.AOE);
    }

    public Collection<SwordSkill> skills() {
        return this.skills.values();
    }

    public SwordSkill skill(String key) {
        return this.skills.get(key);
    }

    public boolean isSword(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.plugin.trainingBladeKey())) {
            return true;
        }
        return this.allowedWeapons.contains(item.getType());
    }

    private void reloadWeapons() {
        this.allowedWeapons.clear();
        for (String name : this.plugin.getConfig().getStringList("swords.allowed-weapons")) {
            try {
                this.allowedWeapons.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                this.plugin.getLogger().warning("Unknown sword material in config: " + name);
            }
        }
    }

    private void add(String key, String displayName, String description, String modelPath, AbilityCategory category) {
        if (!this.plugin.getConfig().getBoolean("swords.skills." + key + ".enabled", true)) {
            return;
        }
        int stamina = this.plugin.getConfig().getInt("swords.skills." + key + ".stamina-cost", 10);
        long cooldown = this.plugin.getConfig().getLong("swords.skills." + key + ".cooldown-ms", 5000L);
        SwordSkill skill = new SwordSkill(key, displayName, description, modelPath, stamina, cooldown);
        this.skills.put(key, skill);
        if (CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().registerAbility(new AbilityRegistration(
                    CrownsSwordsPlugin.MODULE_KEY,
                    key,
                    displayName,
                    description,
                    modelPath,
                    AbilityType.SWORD,
                    category,
                    stamina,
                    cooldown,
                    null
            ), context -> this.cast(skill, context));
        }
    }

    private AbilityCastResult cast(SwordSkill skill, AbilityCastContext context) {
        Player player = context.player();
        if (!player.hasPermission("crowns.swords.use")) {
            return AbilityCastResult.fail("You do not have permission to use Crowns Swords.");
        }
        if (this.plugin.getConfig().getBoolean("swords.stance-required", true) && !player.isSneaking()) {
            return AbilityCastResult.fail("");
        }
        if (!this.isSword(context.itemInHand())) {
            return AbilityCastResult.fail("");
        }
        if (CrownsAPI.getCooldownService() != null && !CrownsAPI.getCooldownService().isReady(player.getUniqueId(), skill.fullKey())) {
            long seconds = (long) Math.ceil(CrownsAPI.getCooldownService().remainingMillis(player.getUniqueId(), skill.fullKey()) / 1000.0D);
            return AbilityCastResult.fail("That sword art is cooling down for " + seconds + "s.");
        }
        if (CrownsAPI.getResourceMeterService() != null
                && !CrownsAPI.getResourceMeterService().consume("swords:stamina", player.getUniqueId(), skill.staminaCost())) {
            return AbilityCastResult.fail("Not enough stamina for " + skill.displayName() + ".");
        }

        boolean success = switch (skill.key()) {
            case "linear" -> this.linear(player);
            case "horizontal_arc" -> this.horizontalArc(player);
            case "starburst_step" -> this.starburstStep(player);
            case "guard_breaker" -> this.guardBreaker(player);
            case "aegis_parry" -> this.aegisParry(player);
            case "whirling_edge" -> this.whirlingEdge(player);
            default -> false;
        };
        if (!success) {
            if (CrownsAPI.getResourceMeterService() != null) {
                CrownsAPI.getResourceMeterService().restore("swords:stamina", player.getUniqueId(), skill.staminaCost());
            }
            return AbilityCastResult.fail("The sword art missed.");
        }
        if (CrownsAPI.getCooldownService() != null) {
            CrownsAPI.getCooldownService().start(player.getUniqueId(), skill.fullKey(), skill.cooldownMillis());
        }
        return AbilityCastResult.ok();
    }

    private boolean linear(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.linear.range", 4.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.linear.damage", 4.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.75D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 1.35F);
        if (target == null) {
            return false;
        }
        target.damage(damage, player);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 16, 0.25D, 0.35D, 0.25D, 0.1D);
        return true;
    }

    private boolean horizontalArc(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.horizontal_arc.range", 4.0D);
        double degrees = this.plugin.getConfig().getDouble("swords.skills.horizontal_arc.cone-degrees", 100.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.horizontal_arc.damage", 3.0D);
        List<LivingEntity> targets = TargetingHelper.coneLiving(player, range, degrees, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.15D, 0), Particle.SWEEP_ATTACK, 1.8D, 18);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.9F);
        for (LivingEntity target : targets.stream().limit(4).toList()) {
            target.damage(damage, player);
        }
        return !targets.isEmpty();
    }

    private boolean starburstStep(Player player) {
        double distance = this.plugin.getConfig().getDouble("swords.skills.starburst_step.distance", 5.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.starburst_step.damage", 4.5D);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location start = player.getLocation();
        Vector velocity = direction.clone().multiply(0.85D);
        velocity.setY(Math.max(0.08D, velocity.getY() * 0.25D));
        player.setVelocity(velocity);
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 12, 0.8D);
            CrownsAPI.getParticlePatternService().trail(start.clone().add(0, 1, 0), direction, Particle.END_ROD, distance, 0.45D);
        }
        LivingEntity target = TargetingHelper.rayLiving(player, distance, 0.9D, entity -> this.canHit(player, entity));
        if (target != null) {
            target.damage(damage, player);
            target.setVelocity(direction.clone().multiply(0.45D).setY(0.18D));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.8F);
        return true;
    }

    private boolean guardBreaker(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.guard_breaker.range", 4.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.guard_breaker.damage", 5.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.85D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 0.75F);
        if (target == null) {
            return false;
        }
        target.damage(damage, player);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0, true, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, true, true, true));
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 22, 0.25D, 0.45D, 0.25D, 0.12D);
        return true;
    }

    private boolean aegisParry(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 70, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 70, 0, true, false, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(135, 170, 255), 28, 0.75D, 1.1F);
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.ENCHANT, 1.25D, 24);
        }
        for (LivingEntity target : TargetingHelper.radiusLiving(player.getLocation(), 2.5D, entity -> entity instanceof Monster)) {
            Vector knock = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(0.45D);
            knock.setY(0.15D);
            target.setVelocity(knock);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9F, 1.15F);
        return true;
    }

    private boolean whirlingEdge(Player player) {
        double radius = this.plugin.getConfig().getDouble("swords.skills.whirling_edge.radius", 4.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.whirling_edge.damage", 3.5D);
        List<LivingEntity> targets = TargetingHelper.radiusLiving(player.getLocation(), radius, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.SWEEP_ATTACK, radius, 42);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(210, 230, 255), 22, 0.9D, 0.8F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.65F);
        for (LivingEntity target : targets.stream().limit(6).toList()) {
            target.damage(damage, player);
        }
        return !targets.isEmpty();
    }

    private void bladeTrail(Location start, Vector direction, double range) {
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(start, direction, Particle.CRIT, range, 0.35D);
            CrownsAPI.getParticlePatternService().trail(start.clone().add(0, -0.15D, 0), direction, Particle.SWEEP_ATTACK, Math.min(range, 3.0D), 0.75D);
        }
    }

    private boolean canHit(Player player, LivingEntity target) {
        return HitSafety.canDamage(player, target, this.plugin.getConfig().getBoolean("swords.pvp-enabled", false));
    }
}
