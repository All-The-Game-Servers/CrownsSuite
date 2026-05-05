package com.xkstudios.crowns.api;

public enum ServiceState {
    MISSING,
    LOADED,
    DEGRADED,
    READY,
    FAILED;

    public boolean healthy() {
        return this == LOADED || this == READY;
    }
}
