package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.Ghost;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Keeps the death screen shut whilst Ghost is on. The kill packet opens it and closing
// any screen whilst dead opens it again.
@Mixin(Gui.class)
public abstract class GuiMixin {

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
}
