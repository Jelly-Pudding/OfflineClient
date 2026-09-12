package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.AutoRespawn;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A button under the vanilla pair that switches AutoRespawn on from the death screen.
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    // The vanilla buttons sit at a quarter of the height plus 72 and 96.
    private static final int BUTTON_TOP = 120;

    private DeathScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        AutoRespawn module = Modules.get(AutoRespawn.class);
        if (module == null || module.isEnabled() || !module.showsButton()) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("Turn AutoRespawn on"),
                button -> module.setEnabled(true))
            .bounds((width - BUTTON_WIDTH) / 2, height / 4 + BUTTON_TOP, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());
    }
}
