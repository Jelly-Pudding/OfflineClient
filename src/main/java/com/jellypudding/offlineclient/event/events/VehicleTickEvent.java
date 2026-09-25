package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.entity.Entity;

// Fired just before the vehicle the player rides runs its own tick.
// Speed set here moves the vehicle this tick. The player's own tick runs after it.
public final class VehicleTickEvent extends UncancellableEvent {

    private final Entity vehicle;

    public VehicleTickEvent(Entity vehicle) {
        this.vehicle = vehicle;
    }

    public Entity getVehicle() {
        return vehicle;
    }
}
