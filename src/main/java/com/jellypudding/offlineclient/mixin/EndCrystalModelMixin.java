package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// Chams changes how far a crystal bobs and how fast it turns.
@Mixin(EndCrystalModel.class)
public abstract class EndCrystalModelMixin {

    // The height vanilla takes off its bob curve. Only the curve above it is scaled.
    @Unique
    private static final float BOB_BASE = 1.4f;

    @ModifyExpressionValue(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EndCrystalRenderer;getY(F)F"))
    private float onBounce(float original, EndCrystalRenderState state) {
        Chams chams = Modules.get(Chams.class);
        if (chams == null || !chams.reshapesCrystals()) {
            return original;
        }
        return (original + BOB_BASE) * chams.crystalBounce() - BOB_BASE;
    }

    // The first read of the age feeds the spin angle.
    @ModifyExpressionValue(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;ageInTicks:F",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private float onSpin(float original) {
        Chams chams = Modules.get(Chams.class);
        return chams == null || !chams.reshapesCrystals() ? original : original * chams.crystalSpin();
    }
}
