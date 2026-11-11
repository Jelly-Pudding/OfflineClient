package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// A combat module that picks one entity to fight. The module answers isEnabled itself.
public interface Targeting {

    // What it fights right now. A crystal or a boat counts. Null between fights.
    Entity getFought();

    // The living thing it fights right now. Null between fights and whilst what it fights has no life.
    default LivingEntity getTarget() {
        return getFought() instanceof LivingEntity living ? living : null;
    }

    boolean isEnabled();
}
