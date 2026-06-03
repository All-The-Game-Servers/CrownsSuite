package com.xkstudios.crowns.api.action;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface ActionInputService {
    void registerAbility(AbilityRegistration registration, AbilityHandler handler);

    void unregisterPlugin(String pluginKey);

    void bind(UUID playerId, String abilityFullKey, GestureSequence sequence);

    void unbind(UUID playerId, GestureSequence sequence);

    void clearBindings(UUID playerId);

    Map<GestureSequence, String> getBindings(UUID playerId);

    AbilityRegistration getAbility(String abilityFullKey);

    Collection<AbilityRegistration> getAbilities();

    void setDebug(UUID playerId, boolean enabled);

    boolean isDebug(UUID playerId);
}
