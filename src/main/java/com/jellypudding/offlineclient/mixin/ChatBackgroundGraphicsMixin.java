package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyReceiver;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The chat drawn whilst the chat screen is closed.
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess")
public abstract class ChatBackgroundGraphicsMixin {

    private static final String ACCEPT = "Lnet/minecraft/client/gui/ActiveTextCollector;accept"
        + "(Lnet/minecraft/client/gui/TextAlignment;II"
        + "Lnet/minecraft/client/gui/ActiveTextCollector$Parameters;"
        + "Lnet/minecraft/util/FormattedCharSequence;)V";

    @Shadow
    @Final
    private GuiGraphicsExtractor graphics;

    // The head goes in the gap the widened chat left on the left.
    @ModifyReceiver(method = "handleMessage", at = @At(value = "INVOKE", target = ACCEPT))
    private ActiveTextCollector beforeText(ActiveTextCollector collector, TextAlignment alignment,
                                           int x, int y, ActiveTextCollector.Parameters parameters,
                                           FormattedCharSequence text) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null) {
            betterChat.drawHead(graphics, y, ARGB.white(parameters.opacity()));
        }
        return collector;
    }

    @ModifyArg(method = "handleMessage", at = @At(value = "INVOKE", target = ACCEPT), index = 1)
    private int shiftText(int x) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        return betterChat == null ? x : betterChat.headRoom(x);
    }

    @Inject(method = "handleMessage", at = @At("TAIL"))
    private void afterText(int top, float opacity, FormattedCharSequence text,
                           CallbackInfoReturnable<Boolean> cir) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null) {
            betterChat.endLine();
        }
    }
}
