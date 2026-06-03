package com.xkstudios.crowns.api.action;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AbilityLifecycleEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final AbilityRegistration registration;
    private final AbilityCastContext context;
    private final AbilityLifecyclePhase phase;
    private final String message;

    public AbilityLifecycleEvent(AbilityRegistration registration, AbilityCastContext context, AbilityLifecyclePhase phase, String message) {
        this.registration = registration;
        this.context = context;
        this.phase = phase;
        this.message = message == null ? "" : message;
    }

    public AbilityRegistration registration() {
        return this.registration;
    }

    public AbilityCastContext context() {
        return this.context;
    }

    public AbilityLifecyclePhase phase() {
        return this.phase;
    }

    public String message() {
        return this.message;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
