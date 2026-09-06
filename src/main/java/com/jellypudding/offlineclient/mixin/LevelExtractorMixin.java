package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.modules.render.PopChams;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(
        method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("TAIL"))
    private void onExtractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
                                          LevelRenderState level, CallbackInfo ci) {
        PopChams popChams = Modules.active(PopChams.class);
        if (popChams != null) {
            popChams.addGhosts(level, deltaTracker.getGameTimeDeltaPartialTick(false));
        }
    }

    // The cloud colour is worked out here and read by the cloud renderer after.
    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
        at = @At("TAIL"))
    private void onExtract(DeltaTracker deltaTracker, Camera camera, float partialTicks,
                           CallbackInfo ci) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience != null && ambience.paintsClouds()) {
            levelRenderState.cloudColor = ambience.cloudColor();
        }
    }

    @Inject(
        method = "extractBlockDestroyAnimation(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private void onExtractBlockDestroyAnimation(Camera camera, LevelRenderState level, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesBlockCracks()) {
            ci.cancel();
        }
    }
}
