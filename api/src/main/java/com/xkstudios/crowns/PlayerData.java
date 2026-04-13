package com.xkstudios.crowns.data;

import java.time.LocalDate;
import java.util.UUID;

public class PlayerData {
    private final UUID uuid;
    private String name;
    private long balance;
    private long totalEarned;
    private int loginStreak;
    private long lastLoginDay;
    private long lastSalaryTime;
    private long firstJoinAt;
    private long lastJoinAt;
    private long lastQuitAt;
    private long totalPlaytimeSeconds;
    private int miningToday;
    private int combatToday;
    private int todayDate;
    private boolean dirty;

    public PlayerData(
            UUID uuid,
            String name,
            long balance,
            long totalEarned,
            int loginStreak,
            long lastLoginDay,
            long lastSalaryTime,
            long firstJoinAt,
            long lastJoinAt,
            long lastQuitAt,
            long totalPlaytimeSeconds
    ) {
        this.uuid = uuid;
        this.name = name;
        this.balance = balance;
        this.totalEarned = totalEarned;
        this.loginStreak = loginStreak;
        this.lastLoginDay = lastLoginDay;
        this.lastSalaryTime = lastSalaryTime;
        this.firstJoinAt = firstJoinAt;
        this.lastJoinAt = lastJoinAt;
        this.lastQuitAt = lastQuitAt;
        this.totalPlaytimeSeconds = totalPlaytimeSeconds;
    }

    public UUID getUuid() {
        return this.uuid;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
        this.dirty = true;
    }

    public long getBalance() {
        return this.balance;
    }

    public void setBalance(long balance) {
        this.balance = Math.max(0L, balance);
        this.dirty = true;
    }

    public void addBalance(long amount) {
        this.balance += amount;
        if (amount > 0L) {
            this.totalEarned += amount;
        }
        this.dirty = true;
    }

    public boolean withdraw(long amount) {
        if (amount <= 0L || this.balance < amount) {
            return false;
        }
        this.balance -= amount;
        this.dirty = true;
        return true;
    }

    public long getTotalEarned() {
        return this.totalEarned;
    }

    public int getLoginStreak() {
        return this.loginStreak;
    }

    public void setLoginStreak(int loginStreak) {
        this.loginStreak = loginStreak;
        this.dirty = true;
    }

    public long getLastLoginDay() {
        return this.lastLoginDay;
    }

    public void setLastLoginDay(long lastLoginDay) {
        this.lastLoginDay = lastLoginDay;
        this.dirty = true;
    }

    public long getLastSalaryTime() {
        return this.lastSalaryTime;
    }

    public void setLastSalaryTime(long lastSalaryTime) {
        this.lastSalaryTime = lastSalaryTime;
        this.dirty = true;
    }

    public long getFirstJoinAt() {
        return this.firstJoinAt;
    }

    public void setFirstJoinAt(long firstJoinAt) {
        this.firstJoinAt = firstJoinAt;
        this.dirty = true;
    }

    public long getLastJoinAt() {
        return this.lastJoinAt;
    }

    public void setLastJoinAt(long lastJoinAt) {
        this.lastJoinAt = lastJoinAt;
        this.dirty = true;
    }

    public long getLastQuitAt() {
        return this.lastQuitAt;
    }

    public void setLastQuitAt(long lastQuitAt) {
        this.lastQuitAt = lastQuitAt;
        this.dirty = true;
    }

    public long getTotalPlaytimeSeconds() {
        return this.totalPlaytimeSeconds;
    }

    public void addPlaytimeSeconds(long seconds) {
        if (seconds <= 0L) {
            return;
        }
        this.totalPlaytimeSeconds += seconds;
        this.dirty = true;
    }

    public int getMiningToday() {
        return this.miningToday;
    }

    public void addMiningToday() {
        this.checkDayReset();
        this.miningToday++;
    }

    public int getCombatToday() {
        return this.combatToday;
    }

    public void addCombatToday() {
        this.checkDayReset();
        this.combatToday++;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    private void checkDayReset() {
        int today = LocalDate.now().getDayOfYear();
        if (today != this.todayDate) {
            this.todayDate = today;
            this.miningToday = 0;
            this.combatToday = 0;
        }
    }

    public double getDailyMultiplier(String type, int fullLimit, int halfLimit) {
        int count = type.equals("mining") ? this.miningToday : this.combatToday;
        if (count <= fullLimit) {
            return 1.0;
        }
        if (count <= halfLimit) {
            return 0.5;
        }
        return 0.25;
    }
}
