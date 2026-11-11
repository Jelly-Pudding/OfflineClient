package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.modules.misc.Panic;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.util.InputUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    // A handler that takes the key keeps it from the open screen and from the game's own keys.
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long windowHandle, int action, KeyEvent event, CallbackInfo ci) {
        Bind input = Bind.of(event);
        // A key the keyboard has no code for reads the same as no bind at all.
        if (!input.isBound()) {
            return;
        }
        // A debug combo belongs to the game. Any other key pressed with the debug key held still
        // comes through like the debug key itself and every release.
        if (action == InputConstants.PRESS && InputUtil.isDebugCombo(event)) {
            return;
        }
        if (Panic.sleepsThrough(input, action)) {
            return;
        }
        if (OfflineClient.INSTANCE.getEventBus().post(new KeyPressEvent(input, action)).isCancelled()) {
            ci.cancel();
        }
    }
}
