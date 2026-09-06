package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The consumer that draws each chat line. The tag paints the signature bar on the left.
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatLineConsumerMixin {

    // The lines arrive top down. BetterChat needs to know which one is being drawn.
    @Inject(method = "accept(Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;IF)V",
        at = @At("HEAD"))
    private void onLine(GuiMessage.Line line, int index, float alpha, CallbackInfo ci) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null) {
            betterChat.beginLine(line, index);
        }
    }

    @ModifyExpressionValue(method = "accept(Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;IF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;tag()Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;"))
    private GuiMessageTag onLineTag(GuiMessageTag tag) {
        ClearView clearView = Modules.active(ClearView.class);
        return clearView != null && clearView.blocksSignatureBar() ? null : tag;
    }
}
