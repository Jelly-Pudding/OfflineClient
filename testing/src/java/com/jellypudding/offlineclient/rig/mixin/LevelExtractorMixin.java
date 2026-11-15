package com.jellypudding.offlineclient.rig.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// A picture is taken from inside a tick. There the game files its own debug drawings with the
// tick and the picture would never show them. Every frame files them with the frame here the
// way a frame on the screen does.
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

    @Shadow
    public abstract Gizmos.TemporaryCollection collectPerFrameMainThreadGizmos();

    @WrapMethod(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V")
    private void rigFilesWithTheFrame(DeltaTracker delta, Camera camera, float partial, Operation<Void> original) {
        try (Gizmos.TemporaryCollection frame = collectPerFrameMainThreadGizmos()) {
            original.call(delta, camera, partial);
        }
    }
}
