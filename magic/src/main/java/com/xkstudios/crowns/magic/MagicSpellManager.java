package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.action.AbilityCastContext;
import com.xkstudios.crowns.api.action.AbilityCastResult;
import com.xkstudios.crowns.api.action.AbilityRegistration;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final CrownsMagicPlugin plugin;
    private final Map<String, MagicSpell> spells = new LinkedHashMap<>();

    public MagicSpellManager(CrownsMagicPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSpells() {
        this.add("starlight_flicker", "Starlight Flicker", "Flash with orbiting starbursts and quicken your steps.", "lowlight/magic/spells/starlight_flicker");
        this.add("ember_bolt", "Ember Bolt", "Launch a contained ember projectile that harms one target.", "lowlight/magic/spells/ember_bolt");
        this.add("aether_step", "Aether Step", "Blink a short safe distance in the direction you face.", "lowlight/magic/spells/aether_step");
        this.add("verdant_mend", "Verdant Mend", "Call soft green magic to mend your wounds.", "lowlight/magic/spells/verdant_mend");
        this.add("arcane_ward", "Arcane Ward", "Wrap yourself in a short-lived protective ward.", "lowlight/magic/spells/arcane_ward");
        this.add("gravity_snare", "Gravity Snare", "Drag nearby hostile creatures into a slowing field.", "lowlight/magic/spells/gravity_snare");
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

    private void add(String key, String displayName, String description, String modelPath) {
        if (!this.plugin.getConfig().getBoolean("magic.spells." + key + ".enabled", true)) {
            return;
        }
        int mana = this.plugin.getConfig().getInt("magic.spells." + key + ".mana-cost", 10);
        long cooldown = this.plugin.getConfig().getLong("magic.spells." + key + ".cooldown-ms", 5000L);
        MagicSpell spell = new MagicSpell(key, displayName, description, modelPath, mana, cooldown);
        this.spells.put(key, spell);
        if (CrownsAPI.getActionInputService() != null) {
            CrownsAPI.getActionInputService().registerAbility(new AbilityRegistration(
                    CrownsMagicPlugin.MODULE_KEY,
                    key,
                    displayName,
                    description,
                    modelPath,
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
        if (CrownsAPI.getCooldownService() != null && !CrownsAPI.getCooldownService().isReady(player.getUniqueId(), spell.fullKey())) {
            long seconds = (long) Math.ceil(CrownsAPI.getCooldownService().remainingMillis(player.getUniqueId(), spell.fullKey()) / 1000.0D);
            return AbilityCastResult.fail("That spell is cooling down for " + seconds + "s.");
        }
        if (CrownsAPI.getResourceMeterService() != null
                && !CrownsAPI.getResourceMeterService().consume("magic:mana", player.getUniqueId(), spell.manaCost())) {
            return AbilityCastResult.fail("Not enough mana for " + spell.displayName() + ".");
        }

        boolean success = switch (spell.key()) {
            case "starlight_flicker" -> this.starlightFlicker(player);
            case "ember_bolt" -> this.emberBolt(player, context);
            case "aether_step" -> this.aetherStep(player, context);
            case "verdant_mend" -> this.verdantMend(player);
            case "arcane_ward" -> this.arcaneWard(player);
            case "gravity_snare" -> this.gravitySnare(player);
            default -> false;
        };
        if (!success) {
            if (CrownsAPI.getResourceMeterService() != null) {
                CrownsAPI.getResourceMeterService().restore("magic:mana", player.getUniqueId(), spell.manaCost());
            }
            return AbilityCastResult.fail("The spell fizzled.");
        }
        if (CrownsAPI.getCooldownService() != null) {
            CrownsAPI.getCooldownService().start(player.getUniqueId(), spell.fullKey(), spell.cooldownMillis());
        }
        return AbilityCastResult.ok();
    }

    private boolean starlightFlicker(Player player) {
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().starfield(player, 28, 1.15D);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 0, true, true, true));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.6F);
        return true;
    }

    private boolean emberBolt(Player player, AbilityCastContext context) {
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
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.7F, 1.4F);
        return true;
    }

    private boolean aetherStep(Player player, AbilityCastContext context) {
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
            return false;
        }
        World world = player.getWorld();
        world.spawnParticle(Particle.PORTAL, current.add(0, 1, 0), 45, 0.35D, 0.6D, 0.35D, 0.08D);
        player.teleport(destination);
        world.spawnParticle(Particle.REVERSE_PORTAL, destination.clone().add(0, 1, 0), 45, 0.35D, 0.6D, 0.35D, 0.08D);
        world.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.7F);
        return true;
    }

    private boolean verdantMend(Player player) {
        double heal = this.plugin.getConfig().getDouble("magic.spells.verdant_mend.heal", 4.0D);
        double max = player.getMaxHealth();
        player.setHealth(Math.min(max, player.getHealth() + heal));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(94, 214, 109), 34, 0.8D, 1.1F);
            CrownsAPI.getParticlePatternService().ring(player.getLocation(), Particle.HAPPY_VILLAGER, 1.2D, 18);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AZALEA_LEAVES_PLACE, 0.8F, 1.2F);
        return true;
    }

    private boolean arcaneWard(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 0, true, true, true));
        if (CrownsAPI.getParticlePatternService() != null) {
            CrownsAPI.getParticlePatternService().ring(player.getLocation().add(0, 0.2D, 0), Particle.ENCHANT, 1.45D, 30);
            CrownsAPI.getParticlePatternService().dustBurst(player.getLocation().add(0, 1, 0), Color.fromRGB(128, 95, 255), 28, 0.7D, 1.0F);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 0.9F);
        return true;
    }

    private boolean gravitySnare(Player player) {
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
        return affected > 0;
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
