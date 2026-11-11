package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.DebugDrawings;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {

    @Shadow
    @Final
    private List<DebugRenderer.SimpleDebugRenderer> renderers;

    // The drawings DebugDrawings picked join the ones the game builds for itself.
    @Inject(method = "refreshRendererList()V", at = @At("TAIL"))
    private void onRefreshRendererList(CallbackInfo ci) {
        DebugDrawings drawings = Modules.active(DebugDrawings.class);
        if (drawings != null) {
            renderers.addAll(drawings.renderers());
        }
    }
}
