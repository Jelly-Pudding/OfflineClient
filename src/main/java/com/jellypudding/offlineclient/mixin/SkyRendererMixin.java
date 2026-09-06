package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(ClientLevel level, float partialTicks, Camera camera,
                                      SkyRenderState state, CallbackInfo ci) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience == null) {
            return;
        }
        if (ambience.drawsEndSky()) {
            state.skybox = DimensionType.Skybox.END;
        }
        if (ambience.paintsSky()) {
            state.skyColor = ambience.skyColor();
        }
    }
}
