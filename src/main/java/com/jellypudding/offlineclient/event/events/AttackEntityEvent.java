package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.world.entity.Entity;

/**
 * Fired when the player attacks an entity.
 */
public final class AttackEntityEvent extends Event {

    private final Entity target;

    public AttackEntityEvent(Entity target) {
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
