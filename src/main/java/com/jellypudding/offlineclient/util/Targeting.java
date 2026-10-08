package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.LivingEntity;

// A combat module that picks one entity to fight. The module answers isEnabled itself.
public interface Targeting {

    // The entity it fights right now. Null between fights.
    LivingEntity getTarget();

    boolean isEnabled();
}
