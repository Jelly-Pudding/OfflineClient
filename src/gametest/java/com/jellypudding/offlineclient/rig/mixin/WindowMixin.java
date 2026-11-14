package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.WindowPlace;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Puts the window of the test game out of the way the moment it exists.
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow
    @Final
    private long handle;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rigTucksWindowAway(CallbackInfo ci) {
        WindowPlace.tuckAway(handle);
    }
}
