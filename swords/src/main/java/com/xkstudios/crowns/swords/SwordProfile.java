package com.xkstudios.crowns.swords;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SwordProfile {
    private final UUID playerId;
    private final Set<String> learnedSkills = new LinkedHashSet<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final Map<String, Integer> styleXp = new LinkedHashMap<>();
    private final Set<String> completedDaily = new LinkedHashSet<>();
    private int rank = 1;
    private int xp;
    private String dailyDate = "";
    private int dailyArts;
    private int dailyHits;
    private int dailyGuard;

    public SwordProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public Set<String> learnedSkills() {
        return this.learnedSkills;
    }

    public Map<String, String> bindings() {
        return this.bindings;
    }

    public Map<String, Integer> styleXp() {
        return this.styleXp;
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

    public int dailyArts() {
        return this.dailyArts;
    }

    public void setDailyArts(int dailyArts) {
        this.dailyArts = Math.max(0, dailyArts);
    }

    public int dailyHits() {
        return this.dailyHits;
    }

    public void setDailyHits(int dailyHits) {
        this.dailyHits = Math.max(0, dailyHits);
    }

    public int dailyGuard() {
        return this.dailyGuard;
    }

    public void setDailyGuard(int dailyGuard) {
        this.dailyGuard = Math.max(0, dailyGuard);
    }
}
