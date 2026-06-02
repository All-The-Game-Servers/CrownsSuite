package com.xkstudios.crowns.api.action;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AbilityCastEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final AbilityRegistration registration;
    private final AbilityCastContext context;
    private boolean cancelled;
    private String cancelReason = "";

    public AbilityCastEvent(AbilityRegistration registration, AbilityCastContext context) {
        this.registration = registration;
        this.context = context;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public AbilityRegistration registration() {
        return this.registration;
    }

    public AbilityCastContext context() {
        return this.context;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public String cancelReason() {
        return this.cancelReason;
    }

    public void cancel(String reason) {
        this.cancelled = true;
        this.cancelReason = reason == null ? "" : reason;
    }
}
