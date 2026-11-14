package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.RigClock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Lets the rig shorten the time a tick of the test game takes.
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "getTickTargetMillis", at = @At("RETURN"), cancellable = true)
    private void rigHurries(float defaultMillis, CallbackInfoReturnable<Float> cir) {
        if (RigClock.speed() != RigClock.NORMAL) {
            cir.setReturnValue(cir.getReturnValueF() / RigClock.speed());
        }
    }
}
