package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.PopChams;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Inject(
        method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("TAIL"))
    private void onExtractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
                                          LevelRenderState level, CallbackInfo ci) {
        PopChams popChams = Modules.get(PopChams.class);
        if (popChams != null && popChams.isEnabled()) {
            popChams.addGhosts(level, deltaTracker.getGameTimeDeltaPartialTick(false));
        }
    }
}
