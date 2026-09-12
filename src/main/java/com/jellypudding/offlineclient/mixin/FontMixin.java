package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Font.class)
public abstract class FontMixin {

    // Scrambled text picks a random glyph each frame. Plain text keeps its own.
    @ModifyExpressionValue(method = "getGlyph(ILnet/minecraft/network/chat/Style;)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Style;isObfuscated()Z"))
    private boolean onIsObfuscated(boolean obfuscated) {
        // Asked for every glyph drawn. Plain text must not reach the module lookup.
        if (!obfuscated) {
            return false;
        }
        ClearView clearView = Modules.active(ClearView.class);
        return clearView == null || !clearView.blocksMagicText();
    }
}
