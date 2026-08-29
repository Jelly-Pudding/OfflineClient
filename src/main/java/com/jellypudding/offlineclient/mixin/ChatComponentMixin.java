package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        argsOnly = true)
    private Component rewriteMessage(Component message) {
        NameProtect nameProtect = Modules.get(NameProtect.class);
        if (nameProtect != null) {
            message = nameProtect.filter(message);
        }
        AntiSpam antiSpam = Modules.get(AntiSpam.class);
        if (antiSpam != null) {
            message = antiSpam.fold((ChatComponent) (Object) this, message);
        }
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? message : betterChat.decorate(message);
    }

    // Both queues trim to the same hundred lines.
    @ModifyExpressionValue(method = {"addMessageToDisplayQueue", "addMessageToQueue"},
        at = @At(value = "CONSTANT", args = "intValue=100"))
    private int historyLimit(int vanilla) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? vanilla : betterChat.historyLimit(vanilla);
    }

    // Called on a disconnect and by the debug clear key.
    @Inject(method = "clearMessages(Z)V", at = @At("HEAD"), cancellable = true)
    private void onClearMessages(boolean history, CallbackInfo ci) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null && betterChat.keepsHistory()) {
            ci.cancel();
        }
    }
}
