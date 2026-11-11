package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.gui.BookButtons;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A book signed by hand leaves its title behind. The next book offers it beside the title box.
@Mixin(BookSignScreen.class)
public abstract class BookSignScreenMixin extends Screen {

    @Shadow
    private EditBox titleBox;

    private BookSignScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (Conceal.hides(Conceal.Part.SCREENS)) {
            return;
        }
        Button same = BookButtons.sameTitle(this, title -> titleBox.setValue(title));
        if (same != null) {
            addRenderableWidget(same);
        }
    }

    // The game sends the title as the box holds it with the spaces at either end cut off.
    @Inject(method = "saveChanges()V", at = @At("HEAD"))
    private void onSign(CallbackInfo ci) {
        BookButtons.signed(titleBox.getValue().trim());
    }
}
