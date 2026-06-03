package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.action.AbilityCastContext;
import com.xkstudios.crowns.api.action.AbilityCastResult;
import com.xkstudios.crowns.api.action.AbilityCategory;
import com.xkstudios.crowns.api.action.AbilityLifecyclePhase;
import com.xkstudios.crowns.api.action.AbilityRank;
import com.xkstudios.crowns.api.action.AbilityRegistration;
import com.xkstudios.crowns.api.action.AbilityTelemetryCounter;
import com.xkstudios.crowns.api.action.AbilityType;
import com.xkstudios.crowns.api.action.HitSafety;
import com.xkstudios.crowns.api.action.TargetingHelper;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private record SkillOutcome(boolean success, int hits, double damage, boolean guard) {
    }

    private final CrownsSwordsPlugin plugin;
    private final Map<String, SwordSkill> skills = new LinkedHashMap<>();
    private final Map<UUID, Long> riposteWindows = new HashMap<>();
    private final Set<Material> allowedWeapons = new HashSet<>();

    public SwordSkillManager(CrownsSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSkills() {
        this.reloadWeapons();
        this.add("linear", "Linear", "A quick forward thrust with a bright sword trail.", "lowlight/swords/skills/linear", "flash", "Flash Style", AbilityRank.NOVICE, AbilityCategory.STRIKE);
        this.add("horizontal_arc", "Horizontal Arc", "A sweeping side slash that catches nearby enemies.", "lowlight/swords/skills/horizontal_arc", "guard", "Guard Style", AbilityRank.NOVICE, AbilityCategory.AOE);
        this.add("starburst_step", "Starburst Step", "Dash forward and strike with a star-flash impact.", "lowlight/swords/skills/starburst_step", "flash", "Flash Style", AbilityRank.NOVICE, AbilityCategory.DASH);
        this.add("guard_breaker", "Guard Breaker", "A heavy focused cut that weakens one target.", "lowlight/swords/skills/guard_breaker", "guard", "Guard Style", AbilityRank.NOVICE, AbilityCategory.STRIKE);
        this.add("aegis_parry", "Aegis Parry", "Brace for impact and release a small defensive pulse.", "lowlight/swords/skills/aegis_parry", "guard", "Guard Style", AbilityRank.NOVICE, AbilityCategory.GUARD);
        this.add("whirling_edge", "Whirling Edge", "Spin a short-range ring of blade pressure.", "lowlight/swords/skills/whirling_edge", "guard", "Guard Style", AbilityRank.APPRENTICE, AbilityCategory.AOE);
        this.add("rising_cut", "Rising Cut", "Launch one hostile target with an upward slash.", "lowlight/swords/skills/rising_cut", "flash", "Flash Style", AbilityRank.APPRENTICE, AbilityCategory.STRIKE);
        this.add("crescent_lunge", "Crescent Lunge", "Commit to a forward crescent slash with a wide visual arc.", "lowlight/swords/skills/crescent_lunge", "flash", "Flash Style", AbilityRank.ADEPT, AbilityCategory.DASH);
        this.add("phantom_riposte", "Phantom Riposte", "Open a brief counter window that punishes the next attacker.", "lowlight/swords/skills/phantom_riposte", "phantom", "Phantom Style", AbilityRank.NOVICE, AbilityCategory.COUNTER);
        this.add("piercing_flash", "Piercing Flash", "A longer flash-line thrust that rewards clean aim.", "lowlight/swords/skills/piercing_flash", "flash", "Flash Style", AbilityRank.APPRENTICE, AbilityCategory.STRIKE);
        this.add("meteor_slash", "Meteor Slash", "A committed dash cut with a heavier impact flash.", "lowlight/swords/skills/meteor_slash", "flash", "Flash Style", AbilityRank.ADEPT, AbilityCategory.DASH);
        this.add("afterimage_chain", "Afterimage Chain", "Chain quick angled cuts through nearby hostile targets.", "lowlight/swords/skills/afterimage_chain", "flash", "Flash Style", AbilityRank.EXPERT, AbilityCategory.COMBO);
        this.add("iron_wall", "Iron Wall", "Brace into a grounded defensive wall and push enemies back.", "lowlight/swords/skills/iron_wall", "guard", "Guard Style", AbilityRank.APPRENTICE, AbilityCategory.GUARD);
        this.add("counter_cross", "Counter Cross", "Set a disciplined counter stance with a stronger riposte window.", "lowlight/swords/skills/counter_cross", "guard", "Guard Style", AbilityRank.ADEPT, AbilityCategory.COUNTER);
        this.add("shadowstep_cut", "Shadowstep Cut", "Slip sideways and cut with a phantom-blue aftertrail.", "lowlight/swords/skills/shadowstep_cut", "phantom", "Phantom Style", AbilityRank.APPRENTICE, AbilityCategory.DASH);
        this.add("mirage_edge", "Mirage Edge", "Unfold a misleading circular slash around your position.", "lowlight/swords/skills/mirage_edge", "phantom", "Phantom Style", AbilityRank.ADEPT, AbilityCategory.AOE);
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
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.plugin.excaliburKey())) {
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

    private void add(String key, String displayName, String description, String modelPath, String styleKey, String styleName, AbilityRank rank, AbilityCategory category) {
        if (!this.plugin.getConfig().getBoolean("swords.skills." + key + ".enabled", true)) {
            return;
        }
        int stamina = this.plugin.getConfig().getInt("swords.skills." + key + ".stamina-cost", 10);
        long cooldown = this.plugin.getConfig().getLong("swords.skills." + key + ".cooldown-ms", 5000L);
        SwordSkill skill = new SwordSkill(key, displayName, description, modelPath, styleKey, styleName, rank, stamina, cooldown);
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
                    styleKey,
                    styleName,
                    rank,
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
        if (!this.plugin.profiles().isUnlocked(player.getUniqueId(), skill)) {
            return AbilityCastResult.fail(skill.displayName() + " unlocks at " + skill.styleName() + " " + skill.rank().displayName() + " mastery.");
        }
        if (CrownsAPI.getCooldownService() != null && !CrownsAPI.getCooldownService().isReady(player.getUniqueId(), skill.fullKey())) {
            long seconds = (long) Math.ceil(CrownsAPI.getCooldownService().remainingMillis(player.getUniqueId(), skill.fullKey()) / 1000.0D);
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), skill.fullKey(), AbilityTelemetryCounter.COOLDOWN_BLOCKS);
            }
            return AbilityCastResult.fail("That sword art is cooling down for " + seconds + "s.");
        }
        if (CrownsAPI.getResourceMeterService() != null
                && !CrownsAPI.getResourceMeterService().consume("swords:stamina", player.getUniqueId(), skill.staminaCost())) {
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), skill.fullKey(), AbilityTelemetryCounter.RESOURCE_BLOCKS);
            }
            return AbilityCastResult.fail("Not enough stamina for " + skill.displayName() + ".");
        }

        SkillOutcome outcome = switch (skill.key()) {
            case "linear" -> this.linear(player);
            case "horizontal_arc" -> this.horizontalArc(player);
            case "starburst_step" -> this.starburstStep(player);
            case "guard_breaker" -> this.guardBreaker(player);
            case "aegis_parry" -> this.aegisParry(player);
            case "whirling_edge" -> this.whirlingEdge(player);
            case "rising_cut" -> this.risingCut(player);
            case "crescent_lunge" -> this.crescentLunge(player);
            case "phantom_riposte" -> this.phantomRiposte(player);
            case "piercing_flash" -> this.piercingFlash(player);
            case "meteor_slash" -> this.meteorSlash(player);
            case "afterimage_chain" -> this.afterimageChain(player);
            case "iron_wall" -> this.ironWall(player);
            case "counter_cross" -> this.counterCross(player);
            case "shadowstep_cut" -> this.shadowstepCut(player);
            case "mirage_edge" -> this.mirageEdge(player);
            default -> new SkillOutcome(false, 0, 0.0D, false);
        };
        if (!outcome.success()) {
            if (CrownsAPI.getResourceMeterService() != null) {
                CrownsAPI.getResourceMeterService().restore("swords:stamina", player.getUniqueId(), skill.staminaCost());
            }
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), skill.fullKey(), AbilityTelemetryCounter.MISSES);
            }
            return AbilityCastResult.fail("The sword art missed.");
        }
        if (CrownsAPI.getCooldownService() != null) {
            CrownsAPI.getCooldownService().start(player.getUniqueId(), skill.fullKey(), skill.cooldownMillis());
        }
        CrownsAPI.publishAbilityLifecycle(new AbilityRegistration(CrownsSwordsPlugin.MODULE_KEY, skill.key(), skill.displayName(), skill.description(), skill.modelPath(), AbilityType.SWORD, AbilityCategory.COMBO, skill.styleKey(), skill.styleName(), skill.rank(), skill.staminaCost(), skill.cooldownMillis(), null), context, AbilityLifecyclePhase.COOLDOWN_STARTED, "");
        if (CrownsAPI.getAbilityTelemetryService() != null) {
            CrownsAPI.getAbilityTelemetryService().add(player.getUniqueId(), skill.fullKey(), AbilityTelemetryCounter.HITS, outcome.hits());
            CrownsAPI.getAbilityTelemetryService().add(player.getUniqueId(), skill.fullKey(), AbilityTelemetryCounter.DAMAGE_DEALT, Math.round(outcome.damage()));
        }
        this.plugin.profiles().recordPractice(player, skill.key(), skill.styleKey(), outcome.hits(), outcome.guard());
        return AbilityCastResult.ok();
    }

    private SkillOutcome linear(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.linear.range", 4.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.linear.damage", 4.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.75D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 1.35F);
        if (target == null) {
            return new SkillOutcome(false, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 16, 0.25D, 0.35D, 0.25D, 0.1D);
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome horizontalArc(Player player) {
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
        int hits = Math.min(4, targets.size());
        return new SkillOutcome(hits > 0, hits, hits * damage, false);
    }

    private SkillOutcome starburstStep(Player player) {
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
        return new SkillOutcome(true, target == null ? 0 : 1, target == null ? 0.0D : damage, false);
    }

    private SkillOutcome guardBreaker(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.guard_breaker.range", 4.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.guard_breaker.damage", 5.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.85D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 0.75F);
        if (target == null) {
            return new SkillOutcome(false, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0, true, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, true, true, true));
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 22, 0.25D, 0.45D, 0.25D, 0.12D);
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome aegisParry(Player player) {
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
        return new SkillOutcome(true, 0, 0.0D, true);
    }

    private SkillOutcome whirlingEdge(Player player) {
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
        int hits = Math.min(6, targets.size());
        return new SkillOutcome(hits > 0, hits, hits * damage, false);
    }

    private SkillOutcome risingCut(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.rising_cut.range", 4.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.rising_cut.damage", 4.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.8D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 1.25F);
        if (target == null) {
            return new SkillOutcome(false, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.setVelocity(target.getVelocity().add(new Vector(0.0D, 0.55D, 0.0D)));
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 20, 0.2D, 0.55D, 0.2D, 0.12D);
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome crescentLunge(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.crescent_lunge.range", 5.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.crescent_lunge.damage", 4.0D);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Vector velocity = direction.clone().multiply(0.65D);
        velocity.setY(Math.max(0.05D, velocity.getY() * 0.2D));
        player.setVelocity(velocity);
        List<LivingEntity> targets = TargetingHelper.coneLiving(player, range, 130.0D, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(player.getEyeLocation(), direction, Particle.SWEEP_ATTACK, range, 0.4D);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(180, 220, 255), 18, 0.8D, 0.8F);
        }
        for (LivingEntity target : targets.stream().limit(3).toList()) {
            target.damage(damage, player);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.45F);
        int hits = Math.min(3, targets.size());
        return new SkillOutcome(hits > 0, hits, hits * damage, false);
    }

    private SkillOutcome phantomRiposte(Player player) {
        long windowMillis = this.plugin.getConfig().getLong("swords.skills.phantom_riposte.window-ms", 3000L);
        this.riposteWindows.put(player.getUniqueId(), System.currentTimeMillis() + windowMillis);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) Math.max(20L, windowMillis / 50L), 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 20, 0.8D);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(150, 180, 255), 18, 0.6D, 0.8F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.65F, 1.6F);
        return new SkillOutcome(true, 0, 0.0D, true);
    }

    private SkillOutcome piercingFlash(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.piercing_flash.range", 6.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.piercing_flash.damage", 4.5D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.65D, entity -> this.canHit(player, entity));
        this.bladeTrail(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        player.getWorld().spawnParticle(Particle.END_ROD, player.getEyeLocation().add(player.getEyeLocation().getDirection()), 12, 0.12D, 0.12D, 0.12D, 0.03D);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8F, 1.65F);
        if (target == null) {
            return new SkillOutcome(false, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.getWorld().spawnParticle(Particle.FLASH, target.getLocation().add(0, 1, 0), 1);
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome meteorSlash(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.meteor_slash.range", 6.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.meteor_slash.damage", 5.0D);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        player.setVelocity(direction.clone().multiply(0.9D).setY(Math.max(0.1D, direction.getY() * 0.2D)));
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.9D, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(player.getEyeLocation(), direction, Particle.CRIT, range, 0.28D);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(255, 180, 88), 22, 0.75D, 0.9F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9F, 1.25F);
        if (target == null) {
            return new SkillOutcome(true, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.setVelocity(direction.clone().multiply(0.55D).setY(0.25D));
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome afterimageChain(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.afterimage_chain.range", 5.0D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.afterimage_chain.damage", 3.0D);
        List<LivingEntity> targets = TargetingHelper.coneLiving(player, range, 145.0D, entity -> this.canHit(player, entity));
        int hits = 0;
        for (LivingEntity target : targets.stream().limit(4).toList()) {
            target.damage(damage, player);
            target.getWorld().spawnParticle(Particle.END_ROD, target.getLocation().add(0, 1, 0), 10, 0.2D, 0.35D, 0.2D, 0.03D);
            hits++;
        }
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 18, 0.8D);
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.SWEEP_ATTACK, 2.3D, 24);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7F, 1.7F);
        return new SkillOutcome(hits > 0, hits, hits * damage, false);
    }

    private SkillOutcome ironWall(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 110, 1, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 0, true, false, true));
        int pushed = 0;
        for (LivingEntity target : TargetingHelper.radiusLiving(player.getLocation(), 3.0D, entity -> entity instanceof Monster)) {
            Vector knock = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(0.65D);
            knock.setY(0.18D);
            target.setVelocity(knock);
            pushed++;
        }
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.ENCHANT, 1.8D, 32);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(112, 140, 170), 28, 0.8D, 1.1F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 0.8F);
        return new SkillOutcome(true, pushed, 0.0D, true);
    }

    private SkillOutcome counterCross(Player player) {
        long windowMillis = this.plugin.getConfig().getLong("swords.skills.counter_cross.window-ms", 3600L);
        this.riposteWindows.put(player.getUniqueId(), System.currentTimeMillis() + windowMillis);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) Math.max(30L, windowMillis / 50L), 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(210, 230, 255), 20, 0.7D, 0.9F);
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.CRIT, 1.35D, 20);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8F, 1.0F);
        return new SkillOutcome(true, 0, 0.0D, true);
    }

    private SkillOutcome shadowstepCut(Player player) {
        double range = this.plugin.getConfig().getDouble("swords.skills.shadowstep_cut.range", 4.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.shadowstep_cut.damage", 4.0D);
        Vector forward = player.getEyeLocation().getDirection().normalize();
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX()).normalize().multiply(player.getUniqueId().getLeastSignificantBits() % 2L == 0L ? 0.55D : -0.55D);
        player.setVelocity(forward.clone().multiply(0.35D).add(side).setY(0.08D));
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.85D, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(player.getEyeLocation(), forward, Particle.REVERSE_PORTAL, range, 0.38D);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7F, 1.35F);
        if (target == null) {
            return new SkillOutcome(true, 0, 0.0D, false);
        }
        target.damage(damage, player);
        target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0), 18, 0.25D, 0.4D, 0.25D, 0.04D);
        return new SkillOutcome(true, 1, damage, false);
    }

    private SkillOutcome mirageEdge(Player player) {
        double radius = this.plugin.getConfig().getDouble("swords.skills.mirage_edge.radius", 3.5D);
        double damage = this.plugin.getConfig().getDouble("swords.skills.mirage_edge.damage", 3.25D);
        List<LivingEntity> targets = TargetingHelper.radiusLiving(player.getLocation(), radius, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 24, 0.95D);
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.REVERSE_PORTAL, radius, 36);
        }
        for (LivingEntity target : targets.stream().limit(5).toList()) {
            target.damage(damage, player);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.75F, 1.45F);
        int hits = Math.min(5, targets.size());
        return new SkillOutcome(hits > 0, hits, hits * damage, false);
    }

    public boolean handleIncomingDamage(Player player, LivingEntity attacker) {
        Long expiresAt = this.riposteWindows.get(player.getUniqueId());
        if (expiresAt == null || System.currentTimeMillis() > expiresAt) {
            this.riposteWindows.remove(player.getUniqueId());
            return false;
        }
        this.riposteWindows.remove(player.getUniqueId());
        double damage = this.plugin.getConfig().getDouble("swords.skills.phantom_riposte.damage", 4.0D);
        if (attacker != null && this.canHit(player, attacker)) {
            attacker.damage(damage, player);
            Vector knock = attacker.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(0.5D).setY(0.2D);
            attacker.setVelocity(knock);
            attacker.getWorld().spawnParticle(Particle.FLASH, attacker.getLocation().add(0, 1, 0), 1);
            this.plugin.profiles().recordPractice(player, "phantom_riposte", "phantom", 1, true);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7F, 1.5F);
        return true;
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
