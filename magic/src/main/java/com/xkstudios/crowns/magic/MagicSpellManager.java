package com.xkstudios.crowns.magic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class MagicSpellManager {
    private record SpellOutcome(boolean success, int hits, double damage, boolean support) {
    }

    private final CrownsMagicPlugin plugin;
    private final Map<String, MagicSpell> spells = new LinkedHashMap<>();

    public MagicSpellManager(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSpells() {
        this.add("starlight_flicker", "Starlight Flicker", "Flash with orbiting starbursts and quicken your steps.", "lowlight/magic/spells/starlight_flicker", "astral", "Astral", AbilityRank.NOVICE, AbilityCategory.COMBO);
        this.add("ember_bolt", "Ember Bolt", "Launch a contained ember projectile that harms one target.", "lowlight/magic/spells/ember_bolt", "elemental", "Elemental", AbilityRank.NOVICE, AbilityCategory.STRIKE);
        this.add("aether_step", "Aether Step", "Blink a short safe distance in the direction you face.", "lowlight/magic/spells/aether_step", "astral", "Astral", AbilityRank.NOVICE, AbilityCategory.DASH);
        this.add("verdant_mend", "Verdant Mend", "Call soft green magic to mend your wounds.", "lowlight/magic/spells/verdant_mend", "restoration", "Restoration", AbilityRank.NOVICE, AbilityCategory.GUARD);
        this.add("arcane_ward", "Arcane Ward", "Wrap yourself in a short-lived protective ward.", "lowlight/magic/spells/arcane_ward", "restoration", "Restoration", AbilityRank.NOVICE, AbilityCategory.GUARD);
        this.add("gravity_snare", "Gravity Snare", "Drag nearby hostile creatures into a slowing field.", "lowlight/magic/spells/gravity_snare", "astral", "Astral", AbilityRank.NOVICE, AbilityCategory.AOE);
        this.add("starfall_spark", "Starfall Spark", "Burst nearby hostile creatures with glittering starfire.", "lowlight/magic/spells/starfall_spark", "astral", "Astral", AbilityRank.APPRENTICE, AbilityCategory.AOE);
        this.add("moonlit_veil", "Moonlit Veil", "Wrap yourself in a brief silver defensive shimmer.", "lowlight/magic/spells/moonlit_veil", "astral", "Astral", AbilityRank.ADEPT, AbilityCategory.GUARD);
        this.add("astral_lance", "Astral Lance", "Fire a piercing astral line with strict timing.", "lowlight/magic/spells/astral_lance", "astral", "Astral", AbilityRank.EXPERT, AbilityCategory.STRIKE);
        this.add("flame_wave", "Flame Wave", "Sweep a short cone of ember pressure across hostile creatures.", "lowlight/magic/spells/flame_wave", "elemental", "Elemental", AbilityRank.APPRENTICE, AbilityCategory.AOE);
        this.add("wind_step", "Wind Step", "Ride a controlled gust for quick movement and safe falling.", "lowlight/magic/spells/wind_step", "elemental", "Elemental", AbilityRank.APPRENTICE, AbilityCategory.DASH);
        this.add("stone_skin", "Stone Skin", "Harden your stance with earthen resistance.", "lowlight/magic/spells/stone_skin", "elemental", "Elemental", AbilityRank.ADEPT, AbilityCategory.GUARD);
        this.add("cleansing_light", "Cleansing Light", "Burn away common hostile effects with soft restorative light.", "lowlight/magic/spells/cleansing_light", "restoration", "Restoration", AbilityRank.APPRENTICE, AbilityCategory.GUARD);
        this.add("renewing_circle", "Renewing Circle", "Open a gentle circle that mends nearby allies.", "lowlight/magic/spells/renewing_circle", "restoration", "Restoration", AbilityRank.ADEPT, AbilityCategory.AOE);
        this.add("stellar_beacon", "Stellar Beacon", "Mark yourself with guiding starlight and improved awareness.", "lowlight/magic/spells/stellar_beacon", "astral", "Astral", AbilityRank.APPRENTICE, AbilityCategory.COMBO);
        this.add("void_tether", "Void Tether", "Snare one hostile creature with a heavy astral pull.", "lowlight/magic/spells/void_tether", "astral", "Astral", AbilityRank.EXPERT, AbilityCategory.AOE);
    }

    public Collection<MagicSpell> spells() {
        return this.spells.values();
    }

    public MagicSpell spell(String key) {
        return this.spells.get(key);
    }

    public boolean isFocus(org.bukkit.inventory.ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(this.plugin.focusKey());
    }

    private void add(String key, String displayName, String description, String modelPath, String schoolKey, String schoolName, AbilityRank rank, AbilityCategory category) {
        if (!this.plugin.getConfig().getBoolean("magic.spells." + key + ".enabled", true)) {
            return;
        }
        int mana = this.plugin.getConfig().getInt("magic.spells." + key + ".mana-cost", 10);
        long cooldown = this.plugin.getConfig().getLong("magic.spells." + key + ".cooldown-ms", 5000L);
        MagicSpell spell = new MagicSpell(key, displayName, description, modelPath, schoolKey, schoolName, rank, mana, cooldown);
        this.spells.put(key, spell);
        if (CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().registerAbility(new AbilityRegistration(
                    CrownsMagicPlugin.MODULE_KEY,
                    key,
                    displayName,
                    description,
                    modelPath,
                    AbilityType.MAGIC,
                    category,
                    schoolKey,
                    schoolName,
                    rank,
                    mana,
                    cooldown,
                    null
            ), context -> this.cast(spell, context));
        }
    }

    private AbilityCastResult cast(MagicSpell spell, AbilityCastContext context) {
        Player player = context.player();
        if (!player.hasPermission("crowns.magic.use")) {
            return AbilityCastResult.fail("You do not have permission to use Crowns Magic.");
        }
        if (this.plugin.getConfig().getBoolean("magic.focus-required", true) && !this.isFocus(context.itemInHand())) {
            return AbilityCastResult.fail("");
        }
        if (!this.plugin.profiles().isUnlocked(player.getUniqueId(), spell)) {
            return AbilityCastResult.fail(spell.displayName() + " unlocks at " + spell.schoolName() + " " + spell.rank().displayName() + " mastery.");
        }
        if (CrownsAPI.getCooldownService() != null && !CrownsAPI.getCooldownService().isReady(player.getUniqueId(), spell.fullKey())) {
            long seconds = (long) Math.ceil(CrownsAPI.getCooldownService().remainingMillis(player.getUniqueId(), spell.fullKey()) / 1000.0D);
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), spell.fullKey(), AbilityTelemetryCounter.COOLDOWN_BLOCKS);
            }
            return AbilityCastResult.fail("That spell is cooling down for " + seconds + "s.");
        }
        if (CrownsAPI.getResourceMeterService() != null
                && !CrownsAPI.getResourceMeterService().consume("magic:mana", player.getUniqueId(), spell.manaCost())) {
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), spell.fullKey(), AbilityTelemetryCounter.RESOURCE_BLOCKS);
            }
            return AbilityCastResult.fail("Not enough mana for " + spell.displayName() + ".");
        }

        SpellOutcome outcome = switch (spell.key()) {
            case "starlight_flicker" -> this.starlightFlicker(player);
            case "ember_bolt" -> this.emberBolt(player, context);
            case "aether_step" -> this.aetherStep(player, context);
            case "verdant_mend" -> this.verdantMend(player);
            case "arcane_ward" -> this.arcaneWard(player);
            case "gravity_snare" -> this.gravitySnare(player);
            case "starfall_spark" -> this.starfallSpark(player);
            case "moonlit_veil" -> this.moonlitVeil(player);
            case "astral_lance" -> this.astralLance(player, context);
            case "flame_wave" -> this.flameWave(player);
            case "wind_step" -> this.windStep(player, context);
            case "stone_skin" -> this.stoneSkin(player);
            case "cleansing_light" -> this.cleansingLight(player);
            case "renewing_circle" -> this.renewingCircle(player);
            case "stellar_beacon" -> this.stellarBeacon(player);
            case "void_tether" -> this.voidTether(player);
            default -> new SpellOutcome(false, 0, 0.0D, false);
        };
        if (!outcome.success()) {
            if (CrownsAPI.getResourceMeterService() != null) {
                CrownsAPI.getResourceMeterService().restore("magic:mana", player.getUniqueId(), spell.manaCost());
            }
            if (CrownsAPI.getAbilityTelemetryService() != null) {
                CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), spell.fullKey(), AbilityTelemetryCounter.MISSES);
            }
            return AbilityCastResult.fail("The spell fizzled.");
        }
        if (CrownsAPI.getCooldownService() != null) {
            CrownsAPI.getCooldownService().start(player.getUniqueId(), spell.fullKey(), spell.cooldownMillis());
        }
        CrownsAPI.publishAbilityLifecycle(new AbilityRegistration(CrownsMagicPlugin.MODULE_KEY, spell.key(), spell.displayName(), spell.description(), spell.modelPath(), AbilityType.MAGIC, AbilityCategory.COMBO, spell.schoolKey(), spell.schoolName(), spell.rank(), spell.manaCost(), spell.cooldownMillis(), null), context, AbilityLifecyclePhase.COOLDOWN_STARTED, "");
        if (CrownsAPI.getAbilityTelemetryService() != null) {
            CrownsAPI.getAbilityTelemetryService().add(player.getUniqueId(), spell.fullKey(), AbilityTelemetryCounter.HITS, outcome.hits());
            CrownsAPI.getAbilityTelemetryService().add(player.getUniqueId(), spell.fullKey(), AbilityTelemetryCounter.DAMAGE_DEALT, Math.round(outcome.damage()));
        }
        this.plugin.profiles().recordPractice(player, spell.key(), spell.schoolKey(), outcome.hits(), outcome.support());
        return AbilityCastResult.ok();
    }

    private SpellOutcome starlightFlicker(Player player) {
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 28, 1.15D);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 0, true, true, true));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.6F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome emberBolt(Player player, AbilityCastContext context) {
        double range = this.plugin.getConfig().getDouble("magic.spells.ember_bolt.range", 18.0D);
        double damage = this.plugin.getConfig().getDouble("magic.spells.ember_bolt.damage", 4.0D);
        Vector direction = context.direction().clone().normalize();
        Location start = context.origin().clone().add(direction.clone().multiply(0.8D));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(start, direction, Particle.FLAME, range, 0.45D);
        }
        RayTraceResult result = player.getWorld().rayTraceEntities(start, direction, range, 0.7D, entity -> {
            if (!(entity instanceof LivingEntity living) || living.equals(player)) {
                return false;
            }
            return this.plugin.getConfig().getBoolean("magic.pvp-enabled", false) || !(living instanceof Player);
        });
        if (result != null && result.getHitEntity() instanceof LivingEntity target) {
            target.damage(damage, player);
            target.setFireTicks(this.plugin.getConfig().getInt("magic.spells.ember_bolt.ignite-ticks", 40));
            target.getWorld().spawnParticle(Particle.LAVA, target.getLocation().add(0, 1, 0), 8, 0.25D, 0.4D, 0.25D, 0.0D);
            player.getWorld().playSound(target.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.5F, 1.9F);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.7F, 1.4F);
            return new SpellOutcome(true, 1, damage, false);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.7F, 1.4F);
        return new SpellOutcome(true, 0, 0.0D, false);
    }

    private SpellOutcome aetherStep(Player player, AbilityCastContext context) {
        double distance = this.plugin.getConfig().getDouble("magic.spells.aether_step.distance", 6.0D);
        Vector direction = context.direction().clone().normalize();
        Location current = player.getLocation();
        Location destination = current.clone();
        for (double step = 1.0D; step <= distance; step += 0.5D) {
            Location candidate = current.clone().add(direction.clone().multiply(step));
            if (this.safeTeleportLocation(candidate)) {
                destination = candidate;
            } else {
                break;
            }
        }
        if (destination.distanceSquared(current) < 1.0D) {
            return new SpellOutcome(false, 0, 0.0D, false);
        }
        World world = player.getWorld();
        world.spawnParticle(Particle.PORTAL, current.add(0, 1, 0), 45, 0.35D, 0.6D, 0.35D, 0.08D);
        player.teleport(destination);
        world.spawnParticle(Particle.REVERSE_PORTAL, destination.clone().add(0, 1, 0), 45, 0.35D, 0.6D, 0.35D, 0.08D);
        world.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.7F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome verdantMend(Player player) {
        double heal = this.plugin.getConfig().getDouble("magic.spells.verdant_mend.heal", 4.0D);
        double max = player.getMaxHealth();
        player.setHealth(Math.min(max, player.getHealth() + heal));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(94, 214, 109), 34, 0.8D, 1.1F);
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.HAPPY_VILLAGER, 1.2D, 18);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AZALEA_LEAVES_PLACE, 0.8F, 1.2F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome arcaneWard(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.ENCHANT, 1.45D, 30);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(128, 95, 255), 28, 0.7D, 1.0F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 0.9F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome gravitySnare(Player player) {
        double radius = this.plugin.getConfig().getDouble("magic.spells.gravity_snare.radius", 5.0D);
        int affected = 0;
        for (LivingEntity entity : player.getWorld().getNearbyLivingEntities(player.getLocation(), radius)) {
            if (entity.equals(player) || entity instanceof Player || !(entity instanceof Monster)) {
                continue;
            }
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1, true, true, true));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, true, true, true));
            entity.getWorld().spawnParticle(Particle.PORTAL, entity.getLocation().add(0, 1, 0), 16, 0.25D, 0.35D, 0.25D, 0.05D);
            affected++;
        }
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.REVERSE_PORTAL, radius, 48);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.7F, 0.7F);
        return new SpellOutcome(affected > 0, affected, 0.0D, false);
    }

    private SpellOutcome starfallSpark(Player player) {
        double radius = this.plugin.getConfig().getDouble("magic.spells.starfall_spark.radius", 4.0D);
        double damage = this.plugin.getConfig().getDouble("magic.spells.starfall_spark.damage", 3.0D);
        int hits = 0;
        for (LivingEntity target : TargetingHelper.radiusLiving(player.getLocation(), radius, entity -> this.canHit(player, entity) && entity instanceof Monster)) {
            target.damage(damage, player);
            target.getWorld().spawnParticle(Particle.END_ROD, target.getLocation().add(0, 1, 0), 12, 0.35D, 0.5D, 0.35D, 0.02D);
            target.getWorld().spawnParticle(Particle.ENCHANT, target.getLocation().add(0, 1, 0), 8, 0.35D, 0.5D, 0.35D, 0.02D);
            hits++;
        }
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.END_ROD, radius, 42);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(180, 210, 255), 30, 1.0D, 0.9F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 1.95F);
        return new SpellOutcome(hits > 0, hits, hits * damage, false);
    }

    private SpellOutcome moonlitVeil(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 110, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 110, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 32, 1.0D);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(220, 220, 255), 32, 0.8D, 0.8F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.6F, 1.8F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome astralLance(Player player, AbilityCastContext context) {
        double range = this.plugin.getConfig().getDouble("magic.spells.astral_lance.range", 22.0D);
        double damage = this.plugin.getConfig().getDouble("magic.spells.astral_lance.damage", 6.0D);
        Vector direction = context.direction().clone().normalize();
        Location start = context.origin().clone().add(direction.clone().multiply(0.5D));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(start, direction, Particle.END_ROD, range, 0.25D);
            CrownsAPI.getParticlePatternService().trail(start.clone().add(0, -0.08D, 0), direction, Particle.ENCHANT, range, 0.35D);
        }
        int hits = 0;
        for (LivingEntity target : player.getWorld().getNearbyLivingEntities(start, range, range, range)) {
            if (!this.canHit(player, target)) {
                continue;
            }
            Vector toTarget = target.getLocation().add(0, 1, 0).toVector().subtract(start.toVector());
            double projection = toTarget.dot(direction);
            if (projection < 0.0D || projection > range) {
                continue;
            }
            double distanceFromLine = toTarget.clone().subtract(direction.clone().multiply(projection)).length();
            if (distanceFromLine > 0.9D) {
                continue;
            }
            target.damage(damage, player);
            target.getWorld().spawnParticle(Particle.FLASH, target.getLocation().add(0, 1, 0), 1);
            hits++;
            if (hits >= 3) {
                break;
            }
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8F, 1.6F);
        return new SpellOutcome(hits > 0, hits, hits * damage, false);
    }

    private SpellOutcome flameWave(Player player) {
        double range = this.plugin.getConfig().getDouble("magic.spells.flame_wave.range", 5.0D);
        double damage = this.plugin.getConfig().getDouble("magic.spells.flame_wave.damage", 3.5D);
        List<LivingEntity> targets = TargetingHelper.coneLiving(player, range, 115.0D, entity -> this.canHit(player, entity));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.15D, 0), Particle.FLAME, 2.2D, 28);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(255, 116, 48), 26, 0.9D, 1.0F);
        }
        for (LivingEntity target : targets.stream().limit(5).toList()) {
            target.damage(damage, player);
            target.setFireTicks(Math.max(target.getFireTicks(), 40));
            target.getWorld().spawnParticle(Particle.LAVA, target.getLocation().add(0, 1, 0), 6, 0.2D, 0.35D, 0.2D, 0.0D);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8F, 0.95F);
        int hits = Math.min(5, targets.size());
        return new SpellOutcome(hits > 0, hits, hits * damage, false);
    }

    private SpellOutcome windStep(Player player, AbilityCastContext context) {
        Vector direction = context.direction().clone().normalize();
        Vector velocity = direction.multiply(this.plugin.getConfig().getDouble("magic.spells.wind_step.force", 0.8D));
        velocity.setY(Math.max(0.28D, velocity.getY() + 0.18D));
        player.setVelocity(velocity);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 90, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 70, 0, true, true, true));
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.3D, 0), 35, 0.45D, 0.2D, 0.45D, 0.03D);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.7F, 1.35F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome stoneSkin(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 140, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(116, 95, 72), 34, 0.8D, 1.1F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_DEEPSLATE_PLACE, 0.9F, 0.85F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome cleansingLight(Player player) {
        int removed = 0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.POISON)
                    || effect.getType().equals(PotionEffectType.WITHER)
                    || effect.getType().equals(PotionEffectType.SLOWNESS)
                    || effect.getType().equals(PotionEffectType.WEAKNESS)
                    || effect.getType().equals(PotionEffectType.DARKNESS)) {
                player.removePotionEffect(effect.getType());
                removed++;
            }
        }
        double heal = this.plugin.getConfig().getDouble("magic.spells.cleansing_light.heal", 2.0D);
        player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 28, 0.45D, 0.6D, 0.45D, 0.02D);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6F, 1.7F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome renewingCircle(Player player) {
        double radius = this.plugin.getConfig().getDouble("magic.spells.renewing_circle.radius", 4.5D);
        double heal = this.plugin.getConfig().getDouble("magic.spells.renewing_circle.heal", 3.0D);
        int healed = 0;
        for (LivingEntity entity : TargetingHelper.radiusLiving(player.getLocation(), radius, entity -> entity instanceof Player)) {
            entity.setHealth(Math.min(entity.getMaxHealth(), entity.getHealth() + heal));
            entity.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, entity.getLocation().add(0, 1, 0), 14, 0.3D, 0.45D, 0.3D, 0.02D);
            healed++;
        }
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.HAPPY_VILLAGER, radius, 44);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(124, 255, 178), 34, 1.0D, 0.9F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7F, 1.3F);
        return new SpellOutcome(healed > 0, 0, 0.0D, true);
    }

    private SpellOutcome stellarBeacon(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 220, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 38, 1.25D);
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.END_ROD, 1.6D, 32);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 2.0F);
        return new SpellOutcome(true, 0, 0.0D, true);
    }

    private SpellOutcome voidTether(Player player) {
        double range = this.plugin.getConfig().getDouble("magic.spells.void_tether.range", 14.0D);
        double damage = this.plugin.getConfig().getDouble("magic.spells.void_tether.damage", 3.0D);
        LivingEntity target = TargetingHelper.rayLiving(player, range, 0.9D, entity -> this.canHit(player, entity));
        if (target == null) {
            return new SpellOutcome(false, 0, 0.0D, false);
        }
        Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(0.35D);
        pull.setY(0.12D);
        target.setVelocity(pull);
        target.damage(damage, player);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 90, 1, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().trail(player.getEyeLocation(), player.getEyeLocation().getDirection(), Particle.REVERSE_PORTAL, range, 0.35D);
        }
        target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0), 34, 0.35D, 0.55D, 0.35D, 0.08D);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65F, 0.65F);
        return new SpellOutcome(true, 1, damage, false);
    }

    private boolean canHit(Player player, LivingEntity target) {
        if (target instanceof Player) {
            return HitSafety.canDamage(player, target, this.plugin.getConfig().getBoolean("magic.pvp-enabled", false));
        }
        return target instanceof Monster;
    }

    private boolean safeTeleportLocation(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return ground.getType().isSolid()
                && !feet.getType().isSolid()
                && !head.getType().isSolid()
                && feet.getWorld().rayTraceBlocks(location.clone().add(0, 1, 0), new Vector(0, -1, 0), 2.0D, FluidCollisionMode.NEVER, true) != null;
    }
}
