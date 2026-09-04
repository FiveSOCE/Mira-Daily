package com.mira.daily.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class DailyRewardClaimEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int rewardDay;
    private final int streak;
    private final int bestStreak;
    private final int totalClaims;
    private final boolean protectionUsed;
    private final boolean protectionEarned;

    public DailyRewardClaimEvent(Player player, int rewardDay, int streak, boolean protectionUsed) {
        this(player, rewardDay, streak, streak, 0, protectionUsed, false);
    }

    public DailyRewardClaimEvent(Player player, int rewardDay, int streak, int bestStreak, int totalClaims,
                                 boolean protectionUsed, boolean protectionEarned) {
        this.player = player;
        this.rewardDay = rewardDay;
        this.streak = streak;
        this.bestStreak = bestStreak;
        this.totalClaims = totalClaims;
        this.protectionUsed = protectionUsed;
        this.protectionEarned = protectionEarned;
    }

    public Player player() { return player; }
    public int rewardDay() { return rewardDay; }
    public int streak() { return streak; }
    public int bestStreak() { return bestStreak; }
    public int totalClaims() { return totalClaims; }
    public boolean protectionUsed() { return protectionUsed; }
    public boolean protectionEarned() { return protectionEarned; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
