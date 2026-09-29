package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.FootButtons;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.NoBackground;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    // The loading screen declares no init of its own and has no way out. A join or a dimension
    // change the server never finishes can be left from it.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (!((Object) this instanceof LevelLoadingScreen)) {
            return;
        }
        AutoReconnect module = Modules.get(AutoReconnect.class);
        // A single player world that is still starting has no player yet. Leaving then would take
        // the server away from the wait that reads it.
        if (module == null || !module.showsLoadingButtons() || OfflineClient.MC.player == null) {
            return;
        }
        Screen screen = (Screen) (Object) this;
        if (AutoReconnect.canRejoin()) {
            addRenderableWidget(FootButtons.upper(screen, Component.literal("Reconnect"),
                button -> AutoReconnect.rejoin()));
        }
        addRenderableWidget(FootButtons.lower(screen,
            CommonComponents.disconnectButtonLabel(OfflineClient.MC.isLocalServer()),
            button -> AutoReconnect.leave()));
    }

    // The dark tint drawn over the world behind an open screen.
    @Inject(method = "extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onExtractTransparentBackground(CallbackInfo ci) {
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
        Blur blur = Modules.active(Blur.class);
        if (blur == null) {
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
        if (key == InputConstants.KEY_SPACE) {
            InvWalk invWalk = Modules.get(InvWalk.class);
            if (invWalk != null && invWalk.takesSpace()) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (key >= InputConstants.KEY_RIGHT && key <= InputConstants.KEY_UP) {
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
