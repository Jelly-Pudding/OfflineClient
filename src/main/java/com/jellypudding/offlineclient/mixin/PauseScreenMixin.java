package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A button in the top right corner of the game menu that opens the GUI. A player who lost the
// key or took it off still has a way in.
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Unique
    private static final int WIDTH = 80;

    @Unique
    private static final int HEIGHT = 16;

    @Unique
    private static final int INSET = 4;

    private PauseScreenMixin(Component title) {
        super(title);
    }

    // The bare pause that shows no menu gets no button either.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        if (!((PauseScreen) (Object) this).showsPauseMenu() || gui == null || Conceal.hides(Conceal.Part.SCREENS)) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal(OfflineClient.NAME), button -> gui.open())
            .bounds(width - WIDTH - INSET, INSET, WIDTH, HEIGHT)
            .build());
    }
}
