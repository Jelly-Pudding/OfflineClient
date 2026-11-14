package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.ChatTap;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lets the rig read every line the client writes to the player.
@Mixin(value = ChatUtil.class, remap = false)
public abstract class ChatUtilMixin {

    @Inject(method = "component", at = @At("HEAD"))
    private static void rigHears(Component component, CallbackInfo ci) {
        ChatTap.heard(component.getString());
    }
}
