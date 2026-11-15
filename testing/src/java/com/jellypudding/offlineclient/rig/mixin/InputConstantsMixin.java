package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.Controls;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The game asks the keyboard itself whether a key is down. A key the rig holds counts as down
// there too. The real keyboard is never pressed for it.
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {

    @Inject(method = "isKeyDown(I)Z", at = @At("HEAD"), cancellable = true)
    private static void rigHolds(int key, CallbackInfoReturnable<Boolean> cir) {
        if (Controls.holds(key)) {
            cir.setReturnValue(true);
        }
    }
}
