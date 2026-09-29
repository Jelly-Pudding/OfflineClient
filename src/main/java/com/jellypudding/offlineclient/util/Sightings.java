package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

// The entities and blocks already reported in the world the client is in. A new world starts afresh.
public final class Sightings {

    private static final int MAX_REMEMBERED = 1024;

    private final Set<UUID> seen = Collections.newSetFromMap(new BoundedMap<>(MAX_REMEMBERED));
    private final Set<Long> places = Collections.newSetFromMap(new BoundedMap<>(MAX_REMEMBERED));
    private final WorldWatch world = new WorldWatch();

    // True the first time the entity turns up in this world.
    public boolean firstTime(Entity entity) {
        followWorld();
        return seen.add(entity.getUUID());
    }

    // True the first time something standing at this block turns up in this world.
    public boolean firstTime(BlockPos pos) {
        followWorld();
        return places.add(pos.asLong());
    }

    public void clear() {
        seen.clear();
        places.clear();
        world.forget();
    }

    private void followWorld() {
        if (world.changed()) {
            seen.clear();
            places.clear();
        }
    }
}
