package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    /** Lets the chat modules rewrite a line just before it is stored. */
    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        argsOnly = true)
    private Component rewriteMessage(Component message) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return message;
        }
        message = OfflineClient.INSTANCE.getModuleManager().get(NameProtect.class).filter(message);
        return OfflineClient.INSTANCE.getModuleManager().get(AntiSpam.class)
            .fold((ChatComponent) (Object) this, message);
    }
}
