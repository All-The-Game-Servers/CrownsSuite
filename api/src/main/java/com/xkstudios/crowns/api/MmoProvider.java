package com.xkstudios.crowns.api;

import java.util.UUID;

public interface MmoProvider {
    String getProfileSummary(UUID playerId, String playerName);

    String getTopSkillSummary(UUID playerId);

    String getWorldProgressSummary(UUID playerId);
}
