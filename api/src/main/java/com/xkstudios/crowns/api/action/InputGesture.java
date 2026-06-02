package com.xkstudios.crowns.api.action;

public enum InputGesture {
    LEFT_CLICK,
    RIGHT_CLICK,
    SNEAK_LEFT_CLICK,
    SNEAK_RIGHT_CLICK,
    SWAP_HAND,
    SNEAK_SWAP_HAND;

    public String displayName() {
        return switch (this) {
            case LEFT_CLICK -> "Left Click";
            case RIGHT_CLICK -> "Right Click";
            case SNEAK_LEFT_CLICK -> "Sneak + Left Click";
            case SNEAK_RIGHT_CLICK -> "Sneak + Right Click";
            case SWAP_HAND -> "Swap Hand";
            case SNEAK_SWAP_HAND -> "Sneak + Swap Hand";
        };
    }
}
