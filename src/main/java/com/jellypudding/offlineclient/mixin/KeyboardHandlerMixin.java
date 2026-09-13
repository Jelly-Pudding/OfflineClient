package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
    private void onKeyPress(long windowHandle, int action, KeyEvent event, CallbackInfo ci) {
        // An unbound keybind holds this same value.
        if (event.key() == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }
        OfflineClient.INSTANCE.getEventBus()
            .post(new KeyPressEvent(event.key(), action));
    }
}
