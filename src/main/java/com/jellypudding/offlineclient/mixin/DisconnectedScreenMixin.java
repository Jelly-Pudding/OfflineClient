package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Two rows under the vanilla buttons so a disconnect can be answered by hand.
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    private DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private Button offlineclient$reconnect;

    @Unique
    private Button offlineclient$toggle;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        AutoReconnect module = Modules.get(AutoReconnect.class);
        if (module == null || !module.showsButtons() || !AutoReconnect.canReconnect()) {
            return;
        }
        int x = (width - BUTTON_WIDTH) / 2;
        offlineclient$reconnect = addRenderableWidget(
            Button.builder(Component.literal("Reconnect"), button -> module.reconnectNow())
                .bounds(x, height - 52, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        offlineclient$toggle = addRenderableWidget(
            Button.builder(offlineclient$toggleText(module), button -> {
                module.toggleFromScreen();
                offlineclient$toggle.setMessage(offlineclient$toggleText(module));
            }).bounds(x, height - 28, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Unique
    private static Component offlineclient$toggleText(AutoReconnect module) {
        return Component.literal("Auto reconnect " + (module.isEnabled() ? "on" : "off"));
    }

    // The countdown reads live so the button says how long is left.
    @Override
    public void tick() {
        super.tick();
        AutoReconnect module = Modules.get(AutoReconnect.class);
        if (offlineclient$reconnect == null || module == null) {
            return;
        }
        double left = module.secondsLeft();
        offlineclient$reconnect.setMessage(Component.literal(
            left < 0 ? "Reconnect" : "Reconnect (" + left + ")"));
    }
}
