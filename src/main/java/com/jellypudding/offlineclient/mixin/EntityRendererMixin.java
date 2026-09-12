package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.IRenderState;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.modules.render.TrueSight;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    // The Blink copy would fill the screen whilst you still stand where it was made.
    @Inject(
        method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
        at = @At("HEAD"), cancellable = true)
    private void onShouldRender(Entity entity, Frustum frustum, double x, double y, double z,
                                CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof FakePlayer.Body body && body.hidesAroundCamera()
            && body.getBoundingBox().contains(x, y, z)) {
            cir.setReturnValue(false);
            return;
        }
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesEntity(entity)) {
            cir.setReturnValue(false);
        }
    }

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

        Chams chams = Modules.get(Chams.class);
        if (chams != null && chams.applies(entity)) {
            extra.offlineclient$setChams(chams.throughWalls());
            extra.offlineclient$setFlat(chams.flat(entity));
            extra.offlineclient$setTint(chams.tintFor(entity));
        }

        TrueSight trueSight = Modules.get(TrueSight.class);
        if (trueSight != null && trueSight.applies(entity)) {
            extra.offlineclient$setForceVisible(true);
            extra.offlineclient$setTint(trueSight.tint());
        }

        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesNametags()) {
            state.nameTag = null;
        }
        // The vanilla tag over a head carries whatever the server wrote there.
        NameProtect nameProtect = Modules.get(NameProtect.class);
        if (nameProtect != null && state.nameTag != null) {
            state.nameTag = nameProtect.filter(state.nameTag);
        }
    }
}
