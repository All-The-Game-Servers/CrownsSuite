package com.xkstudios.crowns.api.action;

import java.util.UUID;

public record AbilityBinding(UUID playerId, String abilityFullKey, GestureSequence sequence) {
}
