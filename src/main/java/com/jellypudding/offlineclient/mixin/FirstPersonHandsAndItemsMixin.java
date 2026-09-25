package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {

    // A new item rises into view unless the swap is being skipped.
    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"))
    private boolean onShouldReplaceInstantly(boolean original) {
        HandView handView = Modules.get(HandView.class);
        return original || (handView != null && handView.skipsSwap());
    }

    // A swap scale of one skips the ease and swaps the item in instantly.
    @ModifyExpressionValue(method = "tick(Lnet/minecraft/client/player/LocalPlayer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float onSwapScale(float original) {
        HandView handView = Modules.get(HandView.class);
        return handView != null && handView.usesOldAnimations() ? 1f : original;
    }
}
