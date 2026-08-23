package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterTab;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(
        method = "extractPingIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onExtractPingIcon(GuiGraphicsExtractor context, int width, int x, int y,
                                   PlayerInfo info, CallbackInfo ci) {
        BetterTab tab = BetterTab.get();
        if (tab != null && tab.showsPing()) {
            tab.drawPing(context, width, x, y, info);
            ci.cancel();
        }
    }

    @ModifyReturnValue(
        method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
        at = @At("RETURN"))
    private Component onGetNameForDisplay(Component original, PlayerInfo info) {
        BetterTab tab = BetterTab.get();
        return tab == null ? original : tab.decorate(original, info);
    }

    // Vanilla caps the list at eighty sorted players.
    @ModifyConstant(method = "getPlayerInfos()Ljava/util/List;", constant = @Constant(longValue = 80L))
    private long onPlayerLimit(long original) {
        BetterTab tab = BetterTab.get();
        return tab == null ? original : tab.playerLimit();
    }
}
