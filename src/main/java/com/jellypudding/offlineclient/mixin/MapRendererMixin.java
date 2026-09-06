package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MapRenderer.class)
public abstract class MapRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V", at = @At("HEAD"), cancellable = true)
    private void onRender(MapRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                          boolean flat, int light, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesMapContents()) {
            ci.cancel();
        }
    }

    // The markers are gathered into the state and dropped again before anything reads them.
    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/saveddata/maps/MapId;"
        + "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;Lnet/minecraft/client/renderer/state/MapRenderState;)V",
        at = @At("TAIL"))
    private void onExtractRenderState(MapId id, MapItemSavedData data, MapRenderState state, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesMapMarkers()) {
            state.decorations.clear();
        }
    }
}
