package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Chams;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.util.Mth;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Chams changes how far a crystal bobs and how fast it turns.
@Mixin(EndCrystalModel.class)
public abstract class EndCrystalModelMixin {

    // The vanilla bob curve with its height scaled.
    @ModifyExpressionValue(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EndCrystalRenderer;getY(F)F"))
    private float onBounce(float original, EndCrystalRenderState state) {
        Chams chams = Chams.get();
        if (chams == null || !chams.reshapesCrystals()) {
            return original;
        }
        float wave = Mth.sin(state.ageInTicks * 0.2f) / 2f + 0.5f;
        return (wave * wave + wave) * 0.4f * chams.crystalBounce() - 1.4f;
    }

    // The first read of the age feeds the spin angle.
    @ModifyExpressionValue(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;ageInTicks:F",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private float onSpin(float original) {
        Chams chams = Chams.get();
        return chams == null || !chams.reshapesCrystals() ? original : original * chams.crystalSpin();
    }
}
