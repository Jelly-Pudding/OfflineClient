package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.Clipboard;
import com.mojang.blaze3d.platform.ClipboardManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The test game keeps a clipboard of its own. The clipboard of the machine is never read or written.
@Mixin(ClipboardManager.class)
public abstract class ClipboardManagerMixin {

    @Inject(method = "getClipboard", at = @At("HEAD"), cancellable = true)
    private void rigReadsOwnClipboard(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(Clipboard.get());
    }

    @Inject(method = "setClipboard", at = @At("HEAD"), cancellable = true)
    private void rigWritesOwnClipboard(String text, CallbackInfo ci) {
        Clipboard.set(text);
        ci.cancel();
    }
}
