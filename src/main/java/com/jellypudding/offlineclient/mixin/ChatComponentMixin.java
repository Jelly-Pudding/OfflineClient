package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
        return antiSpam == null ? message : antiSpam.fold((ChatComponent) (Object) this, message);
    }
}
