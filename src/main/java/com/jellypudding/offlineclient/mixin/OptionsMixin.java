package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.SteadyFrames;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class OptionsMixin {

    // Every part of the game that draws asks here. The saved setting is never changed.
    @Inject(method = "getEffectiveRenderDistance()I", at = @At("RETURN"), cancellable = true)
    private void onEffectiveRenderDistance(CallbackInfoReturnable<Integer> cir) {
        SteadyFrames steadyFrames = Modules.active(SteadyFrames.class);
        if (steadyFrames != null) {
            cir.setReturnValue(steadyFrames.effective(cir.getReturnValue()));
        }
    }
}
