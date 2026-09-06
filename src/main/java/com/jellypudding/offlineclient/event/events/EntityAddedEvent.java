package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.world.entity.Entity;

// Fired on the main thread the moment the server spawns an entity into the world.
public final class EntityAddedEvent extends Event {

    private final Entity entity;

    public EntityAddedEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }

    @Override
    public void cancel() {
        throw new UnsupportedOperationException("EntityAddedEvent cannot be cancelled");
    }
}
