package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

// Fired on the game thread when the server reports that an entity took a hit.
// A hit that lands whilst the entity is still flashing from the last one is never reported.
public final class EntityDamageEvent extends UncancellableEvent {

    private final Entity entity;
    private final DamageSource source;

    public EntityDamageEvent(Entity entity, DamageSource source) {
        this.entity = entity;
        this.source = source;
    }

    public Entity getEntity() {
        return entity;
    }

    public DamageSource getSource() {
        return source;
    }
}
