package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.Esp;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    /** Colors the vanilla glow outline per entity for the ESP glow style. */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
        at = @At("TAIL"))
    private void onExtractRenderState(Entity entity, EntityRenderState state,
                                      float partialTicks, CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return;
        }
        Esp esp = OfflineClient.INSTANCE.getModuleManager().get(Esp.class);
        if (esp.shouldGlow(entity)) {
            state.outlineColor = esp.glowColor(entity);
        }
    }
}
