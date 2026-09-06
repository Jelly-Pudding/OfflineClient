package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.BossStack;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.util.Iterator;

@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
    private Iterator<LerpingBossEvent> onBars(Iterator<LerpingBossEvent> original) {
        BossStack stack = Modules.get(BossStack.class);
        return stack == null ? original : stack.bars(original);
    }

    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
    private Component onName(Component original) {
        BossStack stack = Modules.get(BossStack.class);
        return stack == null ? original : stack.name(original);
    }

    // Vanilla steps down ten pixels plus a line of text for each bar.
    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = 10))
    private int onGap(int original) {
        BossStack stack = Modules.get(BossStack.class);
        return stack == null ? original : stack.gap();
    }
}
