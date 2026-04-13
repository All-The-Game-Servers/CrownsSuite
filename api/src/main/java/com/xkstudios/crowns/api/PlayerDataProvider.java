package com.xkstudios.crowns.api;

import com.xkstudios.crowns.data.PlayerData;
import java.util.List;
import java.util.UUID;

public interface PlayerDataProvider {
    PlayerData getOrCreate(UUID uuid, String name);

    void save(PlayerData data);

    List<PlayerData> getTopBalances(int limit);
}
