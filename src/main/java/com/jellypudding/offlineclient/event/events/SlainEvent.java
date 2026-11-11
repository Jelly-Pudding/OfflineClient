package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.Entity;

// Fired when a player or mob you hurt a moment ago dies. The body is still in the world. A
// player's death also fires KillEvent straight after.
public final class SlainEvent extends UncancellableEvent {

    private final Entity victim;

    public SlainEvent(Entity victim) {
        this.victim = victim;
    }

    public Entity getVictim() {
        return victim;
    }
}
