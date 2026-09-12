package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A chunk meshed whilst Freecam is on treats every block as see through. The
// camera can then look into rooms the player's side would have culled.
@Mixin(VisGraph.class)
public abstract class VisGraphMixin {

    @Inject(method = "setOpaque(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void onSetOpaque(BlockPos pos, CallbackInfo ci) {
        if (Modules.enabled(Freecam.class)) {
            ci.cancel();
        }
    }
}
