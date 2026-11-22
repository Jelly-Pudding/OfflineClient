package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.gui.CleanUpScreen;
import com.jellypudding.offlineclient.gui.TipButton;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    @Unique
    private static final int WIDTH = 72;

    @Unique
    private static final int HEIGHT = 16;

    @Unique
    private static final int INSET = 4;

    @Shadow
    @Final
    private Screen lastScreen;

    @Unique
    private Button offlineclient$lastServer;

    @Unique
    private Button offlineclient$cleanUp;

    private JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        JoinMultiplayerScreen screen = (JoinMultiplayerScreen) (Object) this;
        offlineclient$lastServer = addRenderableWidget(new TipButton(INSET, INSET, WIDTH, HEIGHT,
            Component.literal("Last server"), this::offlineclient$joinLast,
            () -> "Joins " + AutoReconnect.lastAddress() + " again."));
        // After a removal a fresh screen reads the list again and shows the rows that are left.
        offlineclient$cleanUp = addRenderableWidget(Button.builder(Component.literal("Clean up"),
                button -> minecraft.gui.setScreen(new CleanUpScreen(screen, () -> new JoinMultiplayerScreen(lastScreen))))
            .bounds(width - WIDTH - INSET, INSET, WIDTH, HEIGHT)
            .build());
        offlineclient$place();
    }

    @Inject(method = "repositionElements()V", at = @At("TAIL"))
    private void onReposition(CallbackInfo ci) {
        offlineclient$place();
    }

    // Conceal hides both. Before the first join there is no last server and its button hides too.
    @Unique
    private void offlineclient$place() {
        if (offlineclient$cleanUp == null) {
            return;
        }
        boolean shown = !Conceal.hides(Conceal.Part.SCREENS);
        offlineclient$cleanUp.setX(width - WIDTH - INSET);
        offlineclient$cleanUp.visible = shown;
        offlineclient$lastServer.visible = shown && AutoReconnect.lastAddress() != null;
    }

    // The server list's own entry for the address where it has one. Its choice of server packs
    // comes with it.
    @Unique
    private void offlineclient$joinLast() {
        String last = AutoReconnect.lastAddress();
        if (last == null) {
            return;
        }
        JoinMultiplayerScreen screen = (JoinMultiplayerScreen) (Object) this;
        ServerData listed = screen.getServers().get(last);
        screen.join(listed != null ? listed : new ServerData(last, last, ServerData.Type.OTHER));
    }
}
