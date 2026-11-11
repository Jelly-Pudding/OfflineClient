package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterTab;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
        BetterTab tab = Modules.get(BetterTab.class);
        if (tab != null && tab.showsPing()) {
            tab.drawPing(context, width, x, y, info);
            ci.cancel();
        }
    }

    @ModifyReturnValue(
        method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
        at = @At("RETURN"))
    private Component onGetNameForDisplay(Component original, PlayerInfo info) {
        Component shown = EntityUtil.displayed(original);
        BetterTab tab = Modules.get(BetterTab.class);
        return tab == null ? shown : tab.decorate(shown, info);
    }

    // Vanilla caps the list at eighty sorted players.
    @ModifyConstant(method = "getPlayerInfos()Ljava/util/List;", constant = @Constant(longValue = 80L))
    private long onPlayerLimit(long original) {
        BetterTab tab = Modules.get(BetterTab.class);
        return tab == null ? original : tab.playerLimit();
    }

    // The room each row keeps for the ping icon.
    @ModifyConstant(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
        constant = @Constant(intValue = 13))
    private int onPingRoom(int original) {
        BetterTab tab = Modules.get(BetterTab.class);
        return tab == null ? original : tab.pingRoom(original);
    }

    // The one read of online mode decides both the heads and the room each row keeps for them.
    @ModifyExpressionValue(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;onlineMode()Z"))
    private boolean onOnlineMode(boolean original) {
        BetterTab tab = Modules.get(BetterTab.class);
        return original || (tab != null && tab.showsHeads());
    }

    // Vanilla wraps to a new column after twenty rows.
    @ModifyConstant(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
        constant = @Constant(intValue = 20))
    private int onColumnHeight(int original) {
        BetterTab tab = Modules.get(BetterTab.class);
        return tab == null ? original : tab.columnHeight();
    }
}
