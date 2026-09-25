package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.Entity;

public final class AttackEntityEvent extends UncancellableEvent {

    private final Entity target;

    public AttackEntityEvent(Entity target) {
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
