package com.xkstudios.crowns.magic;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MagicProfile {
    private final UUID playerId;
    private final Set<String> learnedSpells = new LinkedHashSet<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final Map<String, Integer> schoolXp = new LinkedHashMap<>();
    private final Set<String> completedDaily = new LinkedHashSet<>();
    private int rank = 1;
    private int xp;
    private String dailyDate = "";
    private int dailyCasts;
    private int dailyHits;
    private int dailySupport;

    public MagicProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public Set<String> learnedSpells() {
        return this.learnedSpells;
    }

    public Map<String, String> bindings() {
        return this.bindings;
    }

    public Map<String, Integer> schoolXp() {
        return this.schoolXp;
    }

    public Set<String> completedDaily() {
        return this.completedDaily;
    }

    public int rank() {
        return this.rank;
    }

    public void setRank(int rank) {
        this.rank = Math.max(1, Math.min(5, rank));
    }

    public int xp() {
        return this.xp;
    }

    public void setXp(int xp) {
        this.xp = Math.max(0, xp);
    }

    public String dailyDate() {
        return this.dailyDate;
    }

    public void setDailyDate(String dailyDate) {
        this.dailyDate = dailyDate == null ? "" : dailyDate;
    }

    public int dailyCasts() {
        return this.dailyCasts;
    }

    public void setDailyCasts(int dailyCasts) {
        this.dailyCasts = Math.max(0, dailyCasts);
    }

    public int dailyHits() {
        return this.dailyHits;
    }

    public void setDailyHits(int dailyHits) {
        this.dailyHits = Math.max(0, dailyHits);
    }

    public int dailySupport() {
        return this.dailySupport;
    }

    public void setDailySupport(int dailySupport) {
        this.dailySupport = Math.max(0, dailySupport);
    }
}
