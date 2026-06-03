package com.xkstudios.crowns.api.action;

import com.xkstudios.crowns.api.CrownsAPI;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

public class DefaultActionInputService implements ActionInputService, Listener {
    private static final long ANTI_DOUBLE_CAST_MILLIS = 250L;

    private record RegisteredAbility(AbilityRegistration registration, AbilityHandler handler) {
    }

    private record TimedGesture(InputGesture gesture, long at) {
    }

    private final Map<String, RegisteredAbility> abilities = new LinkedHashMap<>();
    private final Map<UUID, Map<GestureSequence, String>> bindings = new HashMap<>();
    private final Map<UUID, Deque<TimedGesture>> recentGestures = new HashMap<>();
    private final Map<UUID, Map<String, Long>> recentCasts = new HashMap<>();
    private final Map<UUID, Boolean> debugPlayers = new HashMap<>();

    @Override
    public void registerAbility(AbilityRegistration registration, AbilityHandler handler) {
        if (registration == null || handler == null) {
            return;
        }
        this.abilities.put(registration.fullKey(), new RegisteredAbility(registration, handler));
    }

    @Override
    public void unregisterPlugin(String pluginKey) {
        if (pluginKey == null || pluginKey.isBlank()) {
            return;
        }
        this.abilities.keySet().removeIf(key -> key.startsWith(pluginKey + ":"));
        for (Map<GestureSequence, String> playerBindings : this.bindings.values()) {
            playerBindings.values().removeIf(key -> key.startsWith(pluginKey + ":"));
        }
    }

    @Override
    public void bind(UUID playerId, String abilityFullKey, GestureSequence sequence) {
        if (playerId == null || abilityFullKey == null || sequence == null || !this.abilities.containsKey(abilityFullKey)) {
            return;
        }
        this.bindings.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>()).put(sequence, abilityFullKey);
    }

    @Override
    public void unbind(UUID playerId, GestureSequence sequence) {
        Map<GestureSequence, String> playerBindings = this.bindings.get(playerId);
        if (playerBindings != null) {
            playerBindings.remove(sequence);
        }
    }

    @Override
    public void clearBindings(UUID playerId) {
        this.bindings.remove(playerId);
        this.recentGestures.remove(playerId);
    }

    @Override
    public Map<GestureSequence, String> getBindings(UUID playerId) {
        return Map.copyOf(this.bindings.getOrDefault(playerId, Map.of()));
    }

    @Override
    public AbilityRegistration getAbility(String abilityFullKey) {
        RegisteredAbility ability = this.abilities.get(abilityFullKey);
        return ability == null ? null : ability.registration();
    }

    @Override
    public Collection<AbilityRegistration> getAbilities() {
        return this.abilities.values().stream().map(RegisteredAbility::registration).toList();
    }

    @Override
    public void setDebug(UUID playerId, boolean enabled) {
        if (enabled) {
            this.debugPlayers.put(playerId, true);
        } else {
            this.debugPlayers.remove(playerId);
        }
    }

    @Override
    public boolean isDebug(UUID playerId) {
        return this.debugPlayers.getOrDefault(playerId, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        InputGesture gesture = null;
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            gesture = event.getPlayer().isSneaking() ? InputGesture.SNEAK_LEFT_CLICK : InputGesture.LEFT_CLICK;
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            gesture = event.getPlayer().isSneaking() ? InputGesture.SNEAK_RIGHT_CLICK : InputGesture.RIGHT_CLICK;
        }
        if (gesture != null && this.dispatch(event.getPlayer(), gesture, event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        InputGesture gesture = event.getPlayer().isSneaking() ? InputGesture.SNEAK_SWAP_HAND : InputGesture.SWAP_HAND;
        if (this.dispatch(event.getPlayer(), gesture, event.getMainHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.recentGestures.remove(event.getPlayer().getUniqueId());
        this.recentCasts.remove(event.getPlayer().getUniqueId());
        this.debugPlayers.remove(event.getPlayer().getUniqueId());
    }

    private boolean dispatch(Player player, InputGesture gesture, ItemStack itemInHand) {
        Map<GestureSequence, String> playerBindings = this.bindings.get(player.getUniqueId());
        if (playerBindings == null || playerBindings.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<TimedGesture> gestures = this.recentGestures.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        gestures.addLast(new TimedGesture(gesture, now));
        while (gestures.size() > 8) {
            gestures.removeFirst();
        }

        boolean matched = playerBindings.entrySet().stream()
                .filter(entry -> this.matches(gestures, entry.getKey(), now))
                .sorted(Comparator.comparingInt((Map.Entry<GestureSequence, String> entry) -> entry.getKey().gestures().size()).reversed())
                .findFirst()
                .map(entry -> this.cast(player, gesture, itemInHand, entry.getKey(), entry.getValue()))
                .orElse(false);
        if (this.isDebug(player.getUniqueId())) {
            player.sendMessage(Component.text("[Crowns Debug] gesture=" + gesture + " matched=" + matched, NamedTextColor.DARK_GRAY));
        }
        return matched;
    }

    private boolean matches(Deque<TimedGesture> gestures, GestureSequence sequence, long now) {
        if (gestures.size() < sequence.gestures().size()) {
            return false;
        }
        TimedGesture[] recent = gestures.toArray(TimedGesture[]::new);
        int offset = recent.length - sequence.gestures().size();
        long firstAt = recent[offset].at();
        if (now - firstAt > sequence.maxIntervalMillis()) {
            return false;
        }
        for (int i = 0; i < sequence.gestures().size(); i++) {
            if (recent[offset + i].gesture() != sequence.gestures().get(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean cast(Player player, InputGesture gesture, ItemStack itemInHand, GestureSequence sequence, String abilityKey) {
        RegisteredAbility ability = this.abilities.get(abilityKey);
        if (ability == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lastCast = this.recentCasts.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).getOrDefault(abilityKey, 0L);
        if (now - lastCast < ANTI_DOUBLE_CAST_MILLIS) {
            this.actionBar(player, "Steady your hands...", NamedTextColor.GRAY);
            return true;
        }
        this.recentCasts.get(player.getUniqueId()).put(abilityKey, now);
        this.recentGestures.remove(player.getUniqueId());
        AbilityCastContext context = new AbilityCastContext(
                player,
                gesture,
                sequence,
                itemInHand,
                player.getEyeLocation(),
                player.getEyeLocation().getDirection()
        );
        CrownsAPI.publishAbilityLifecycle(ability.registration(), context, AbilityLifecyclePhase.ATTEMPTED, "");
        AbilityCastEvent event = new AbilityCastEvent(ability.registration(), context);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            CrownsAPI.publishAbilityLifecycle(ability.registration(), context, AbilityLifecyclePhase.FAILED, event.cancelReason());
            this.telemetry(player, abilityKey, AbilityTelemetryCounter.FIZZLES);
            if (event.cancelReason() != null && !event.cancelReason().isBlank()) {
                player.sendMessage(event.cancelReason());
                this.actionBar(player, event.cancelReason(), NamedTextColor.RED);
            }
            return true;
        }
        AbilityCastResult result = ability.handler().cast(context);
        if (result != null && result.message() != null && !result.message().isBlank()) {
            player.sendMessage(result.message());
        }
        boolean success = result != null && result.success();
        if (success) {
            CrownsAPI.publishAbilityLifecycle(ability.registration(), context, AbilityLifecyclePhase.CAST, "");
            this.telemetry(player, abilityKey, AbilityTelemetryCounter.CASTS);
            this.actionBar(player, ability.registration().displayName(), NamedTextColor.GREEN);
        } else {
            String message = result == null ? "Ability failed." : result.message();
            CrownsAPI.publishAbilityLifecycle(ability.registration(), context, AbilityLifecyclePhase.FAILED, message);
            this.telemetry(player, abilityKey, AbilityTelemetryCounter.FIZZLES);
            if (message != null && !message.isBlank()) {
                this.actionBar(player, message, NamedTextColor.RED);
            }
        }
        if (this.isDebug(player.getUniqueId())) {
            player.sendMessage(Component.text("[Crowns Debug] ability=" + abilityKey + " success=" + success, NamedTextColor.DARK_GRAY));
        }
        return success;
    }

    private void telemetry(Player player, String abilityKey, AbilityTelemetryCounter counter) {
        if (CrownsAPI.getAbilityTelemetryService() != null) {
            CrownsAPI.getAbilityTelemetryService().increment(player.getUniqueId(), abilityKey, counter);
        }
    }

    private void actionBar(Player player, String message, NamedTextColor color) {
        if (message == null || message.isBlank()) {
            return;
        }
        player.sendActionBar(Component.text(message, color));
    }
}
