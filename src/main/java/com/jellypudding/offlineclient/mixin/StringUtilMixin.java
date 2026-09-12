package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(StringUtil.class)
public abstract class StringUtilMixin {

    // The only caller is the chat cut off. Raising it here raises nothing else.
    @ModifyArg(method = "trimChatMessage",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/StringUtil;truncateStringIfNecessary(Ljava/lang/String;IZ)Ljava/lang/String;"),
        index = 1)
    private static int chatLimit(int max) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat != null && betterChat.liftsBoxLimit() ? Integer.MAX_VALUE : max;
    }
}
