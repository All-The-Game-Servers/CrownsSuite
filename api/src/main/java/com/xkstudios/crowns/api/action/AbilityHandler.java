package com.xkstudios.crowns.api.action;

@FunctionalInterface
public interface AbilityHandler {
    AbilityCastResult cast(AbilityCastContext context);
}
