package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.player.Player;

// Fired on the game thread when a player pops a totem and again when one who popped dies.
// The count is the totems popped since their last death.
public final class TotemPopEvent extends UncancellableEvent {

    private final Player player;
    private final int count;
    private final boolean death;

    public TotemPopEvent(Player player, int count, boolean death) {
        this.player = player;
        this.count = count;
        this.death = death;
    }

    public Player getPlayer() {
        return player;
    }

    public int getCount() {
        return count;
    }

    // True when the player has died and the count starts again.
    public boolean isDeath() {
        return death;
    }
}
