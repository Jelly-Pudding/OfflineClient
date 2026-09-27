package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.Entity;

// Fired on the game thread when the server reports a death. The entity is still in the world.
public final class EntityDeathEvent extends UncancellableEvent {

    private final Entity entity;

    public EntityDeathEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }
}
