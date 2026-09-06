package com.jellypudding.offlineclient.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The client's own fractional break progress that the server never echoes back.
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {

    @Accessor("destroyProgress")
    float offlineclient$destroyProgress();

    @Accessor("destroyBlockPos")
    BlockPos offlineclient$destroyBlockPos();
}
