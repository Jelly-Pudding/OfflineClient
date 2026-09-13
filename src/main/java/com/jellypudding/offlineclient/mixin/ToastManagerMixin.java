package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every pop up in the top right corner comes through here.
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void onAddToast(Toast toast, CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksToast(toast)) {
            ci.cancel();
        }
    }
}
