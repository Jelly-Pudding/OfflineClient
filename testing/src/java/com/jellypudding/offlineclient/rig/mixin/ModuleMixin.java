package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.RigConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A control run keeps every module off whichever way a case tries to switch one on.
@Mixin(value = Module.class, remap = false)
public abstract class ModuleMixin {

    @Inject(method = "switchTo", at = @At("HEAD"), cancellable = true)
    private void rigKeepsModulesOff(boolean enabled, CallbackInfoReturnable<Boolean> cir) {
        if (enabled && RigConfig.control()) {
            cir.setReturnValue(false);
        }
    }
}
