package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.NoBackground;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
