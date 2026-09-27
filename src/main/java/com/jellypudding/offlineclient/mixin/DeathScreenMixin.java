package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.AutoRespawn;
import com.jellypudding.offlineclient.modules.player.Ghost;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Buttons under the vanilla pair that switch AutoRespawn or Ghost on from the death screen.
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {

    // The vanilla buttons sit at a quarter of the height plus 72 and 96.
    @Unique
    private static final int BUTTON_TOP = 120;

    // The vanilla pair sit this far apart.
    @Unique
    private static final int BUTTON_GAP = 24;

    private DeathScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int top = height / 4 + BUTTON_TOP;
        AutoRespawn respawn = Modules.get(AutoRespawn.class);
        if (respawn != null && !respawn.isEnabled() && respawn.showsButton()) {
            offlineclient$addButton("Turn AutoRespawn on", top, () -> respawn.setEnabled(true));
            top += BUTTON_GAP;
        }
        Ghost ghost = Modules.get(Ghost.class);
        if (ghost != null && !ghost.isEnabled() && ghost.showsButton()) {
            offlineclient$addButton("Turn Ghost on", top, () -> ghost.setEnabled(true));
        }
    }

    @Unique
    private void offlineclient$addButton(String text, int top, Runnable press) {
        addRenderableWidget(Button.builder(Component.literal(text), button -> press.run())
            .bounds((width - Button.BIG_WIDTH) / 2, top, Button.BIG_WIDTH, Button.DEFAULT_HEIGHT)
            .build());
    }
}
