package com.xkstudios.crowns.event;

public enum EventState {
    SCHEDULED,
    COUNTDOWN,
    LIVE,
    PAUSED,
    ENDED;

    public boolean preLive() {
        return this == SCHEDULED || this == COUNTDOWN;
    }

    public boolean active() {
        return this == LIVE;
    }
}
