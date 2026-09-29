package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.player.Ghost;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Gui.class)
public abstract class GuiMixin {

    // Keeps the death screen shut whilst Ghost is on. The kill packet opens it and closing
    // any screen whilst dead opens it again.
    @ModifyVariable(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At("HEAD"), argsOnly = true)
    private Screen onSetScreen(Screen screen) {
        return screen instanceof DeathScreen && Modules.enabled(Ghost.class) ? null : screen;
    }

    @ModifyExpressionValue(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isDeadOrDying()Z"))
    private boolean deathScreenWanted(boolean dead) {
        return dead && !Modules.enabled(Ghost.class);
    }

    // The last call of the frame after screens and toasts and the debug overlay. HUD covers
    // go on here above all of them.
    @WrapOperation(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;applyCursor(Lcom/mojang/blaze3d/platform/Window;)V"))
    private void onFrameEnd(GuiGraphicsExtractor context, Window window, Operation<Void> original) {
        HudModule hud = Modules.get(HudModule.class);
        if (hud != null) {
            hud.renderOnTop(context);
        }
        original.call(context, window);
    }
}
