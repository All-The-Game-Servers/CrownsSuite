package com.xkstudios.crowns.api;

import java.util.UUID;

public interface EconomyProvider {
    long getBalance(UUID player);

    boolean withdraw(UUID player, long amount);

    void deposit(UUID player, long amount);

    String formatCurrency(long pennies);
}
