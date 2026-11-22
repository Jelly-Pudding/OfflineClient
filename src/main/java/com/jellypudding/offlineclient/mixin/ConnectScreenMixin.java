package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.util.ConnectionLog;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    @Unique
    private static final int STATUS_RISE = 50;

    // The server is remembered on every join. AutoReconnect then knows where to go even
    // when it was switched on after the disconnect.
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
                                          ServerData server, boolean quickPlay,
                                          TransferState transfer, CallbackInfo ci) {
        AutoReconnect.remember(server);
        ConnectionLog.begin();
        NameStore.get().clearChecked();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                      CallbackInfo ci) {
        AutoReconnect module = Modules.get(AutoReconnect.class);
        if (module != null && module.showsConnectLog()) {
            Screen screen = (Screen) (Object) this;
            ConnectionLog.draw(graphics, OfflineClient.MC.font, screen.width, screen.height / 2 - STATUS_RISE);
        }
    }
}
