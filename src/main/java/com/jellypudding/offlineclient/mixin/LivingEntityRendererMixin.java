package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.IRenderState;
import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.render.EntityPipelines;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Applies the flags Chams and TrueSight and PopChams put on a render state.
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Shadow
    public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @ModifyReturnValue(
        method = "isBodyVisible(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z",
        at = @At("RETURN"))
    private boolean onIsBodyVisible(boolean original, LivingEntityRenderState state) {
        return original || ((IRenderState) state).offlineclient$isForceVisible();
    }

    @ModifyReturnValue(
        method = "getModelTint(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)I",
        at = @At("RETURN"))
    private int onGetModelTint(int original, LivingEntityRenderState state) {
        int tint = ((IRenderState) state).offlineclient$getTint();
        return tint == 0 ? original : ARGB.multiply(original, tint);
    }

    @ModifyReturnValue(
        method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
        at = @At("RETURN"))
    private RenderType onGetRenderType(RenderType original, LivingEntityRenderState state,
                                       boolean bodyVisible, boolean translucent, boolean glowing) {
        // Anything else is the outline pass or nothing at all.
        if (!bodyVisible && !translucent) {
            return original;
        }
        IRenderState extra = (IRenderState) state;
        Identifier texture = extra.offlineclient$isFlat() ? Chams.BLANK : getTextureLocation(state);
        if (extra.offlineclient$isChams()) {
            return EntityPipelines.chams(texture);
        }
        int tint = extra.offlineclient$getTint();
        if (extra.offlineclient$isFlat() || (tint != 0 && ARGB.alpha(tint) < 255 && !translucent)) {
            return RenderTypes.entityTranslucent(texture);
        }
        return original;
    }

    // Chams grows or shrinks players after vanilla has set their size.
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"))
    private void onExtractLivingState(LivingEntity entity, LivingEntityRenderState state, float partialTicks,
                                      CallbackInfo ci) {
        Chams chams = Chams.get();
        if (chams != null) {
            state.scale *= chams.scaleFor(entity);
        }
    }
}
