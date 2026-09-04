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
    private final boolean protectionUsed;

    public DailyRewardClaimEvent(Player player, int rewardDay, int streak, boolean protectionUsed) {
        this.player = player;
        this.rewardDay = rewardDay;
        this.streak = streak;
        this.protectionUsed = protectionUsed;
    }

    public Player player() { return player; }
    public int rewardDay() { return rewardDay; }
    public int streak() { return streak; }
    public boolean protectionUsed() { return protectionUsed; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
