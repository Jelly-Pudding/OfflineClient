package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.IRenderState;
import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.TrueSight;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
        at = @At("TAIL"))
    private void onExtractRenderState(Entity entity, EntityRenderState state,
                                      float partialTicks, CallbackInfo ci) {
        // Render states are pooled and still carry last frame's flags.
        IRenderState extra = (IRenderState) state;
        extra.offlineclient$clear();

        Esp esp = Modules.get(Esp.class);
        if (esp != null && esp.shouldGlow(entity)) {
            state.outlineColor = esp.glowColor(entity);
        }

        Chams chams = Chams.get();
        if (chams != null && chams.applies(entity)) {
            extra.offlineclient$setChams(chams.throughWalls());
            extra.offlineclient$setTint(chams.tintFor(entity));
        }

        TrueSight trueSight = TrueSight.get();
        if (trueSight != null && trueSight.applies(entity)) {
            extra.offlineclient$setForceVisible(true);
            extra.offlineclient$setTint(trueSight.tint());
        }
    }
}
