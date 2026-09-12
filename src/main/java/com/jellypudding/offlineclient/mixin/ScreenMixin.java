package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.NoBackground;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    // The dark tint drawn over the world behind an open screen.
    @Inject(method = "extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onExtractTransparentBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        NoBackground noBackground = Modules.get(NoBackground.class);
        if (noBackground != null && noBackground.clears((Screen) (Object) this)) {
            ci.cancel();
        }
    }

    // Blur takes over the vanilla menu blur and picks which screens get it.
    @Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onExtractBlurredBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        Blur blur = Modules.get(Blur.class);
        if (blur == null || !blur.isEnabled()) {
            return;
        }
        ci.cancel();
        if (blur.wants((Screen) (Object) this)) {
            blur.blurHere(graphics);
        }
    }

    // Space presses a focused button and the arrows move the focus. Whilst
    // InvWalk jumps on space or GUIMove turns on the arrows the screen never sees them.
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_SPACE) {
            InvWalk invWalk = Modules.get(InvWalk.class);
            if (invWalk != null && invWalk.takesSpace()) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_UP) {
            GUIMove guiMove = Modules.get(GUIMove.class);
            if (guiMove != null && guiMove.takesArrows()) {
                cir.setReturnValue(true);
            }
        }
    }

    // Chat click events that carry a client command run locally instead of going to the server.
    @Inject(
        method = "clickCommandAction(Lnet/minecraft/client/player/LocalPlayer;Ljava/lang/String;Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At("HEAD"),
        cancellable = true)
    private static void onClickCommand(LocalPlayer player, String command, Screen screen, CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getCommandManager().run(command)) {
            ci.cancel();
        }
    }
}
