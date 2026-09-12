package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The server is noted on every join. AutoReconnect then knows where to go even
// when it was switched on after the disconnect.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
                                          ServerData server, boolean quickPlay,
                                          TransferState transfer, CallbackInfo ci) {
        AutoReconnect.remember(server);
    }
}
