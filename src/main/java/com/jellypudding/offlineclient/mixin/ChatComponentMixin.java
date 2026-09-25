package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Unique
    private static final String ADD_MESSAGE = "addMessage"
        + "(Lnet/minecraft/network/chat/Component;"
        + "Lnet/minecraft/network/chat/MessageSignature;"
        + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
        + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V";

    @Unique
    private static final String EXTRACT =
        "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;"
            + "IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V";

    @Inject(method = ADD_MESSAGE, at = @At("HEAD"), cancellable = true)
    private void onAddMessage(Component message, MessageSignature signature,
                              GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null && betterChat.filters(message)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = ADD_MESSAGE, at = @At("HEAD"), argsOnly = true)
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

    // Vanilla holds the chat forty pixels off the bottom whatever is down there.
    @ModifyExpressionValue(method = EXTRACT, at = @At(value = "CONSTANT", args = "intValue=40"))
    private int chatLift(int vanilla) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? vanilla : betterChat.chatLift(vanilla);
    }

    // The whole chat is shifted by this before a line is laid out.
    @ModifyExpressionValue(method = "lambda$extractRenderState$0(FLorg/joml/Matrix3x2f;)V",
        at = @At(value = "CONSTANT", args = "floatValue=4.0"))
    private static float chatIndent(float vanilla) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? vanilla : betterChat.chatIndent(vanilla);
    }

    // The width the lines are laid out in. A head needs room on the left.
    @ModifyExpressionValue(method = EXTRACT,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(F)I"))
    private int headRoom(int width) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? width : betterChat.headRoom(width);
    }

    // Called on a disconnect and by the debug clear key.
    @Inject(method = "clearMessages(Z)V", at = @At("HEAD"), cancellable = true)
    private void onClearMessages(CallbackInfo ci) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null && betterChat.keepsHistory()) {
            ci.cancel();
        }
    }
}
