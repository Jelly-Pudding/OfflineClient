package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.player.Player;

// Fired when a player you hurt a moment ago dies. The streak already counts this kill.
public final class KillEvent extends UncancellableEvent {

    private final Player victim;
    private final int streak;

    public KillEvent(Player victim, int streak) {
        this.victim = victim;
        this.streak = streak;
    }

    public Player getVictim() {
        return victim;
    }

    public int getStreak() {
        return streak;
    }
}
