package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerMixin {

    @Shadow
    public float deltaTicks;

    @Inject(method = "advanceGameTime(J)I",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;lastMs:J",
            opcode = Opcodes.PUTFIELD,
            ordinal = 0))
    private void onAdvanceGameTime(long timeMillis, CallbackInfoReturnable<Integer> cir) {
        Timer timer = Modules.get(Timer.class);
        if (timer != null) {
            deltaTicks *= timer.getSpeed();
        }
    }
}
