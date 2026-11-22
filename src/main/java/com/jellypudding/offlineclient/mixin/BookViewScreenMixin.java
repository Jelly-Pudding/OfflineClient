package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.gui.BookButtons;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {

    @Shadow
    private BookViewScreen.BookAccess bookAccess;

    @Shadow
    protected abstract void pageBack();

    @Shadow
    protected abstract void pageForward();

    private BookViewScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (!Conceal.hides(Conceal.Part.SCREENS)) {
            addRenderableWidget(BookButtons.copy(this,
                () -> bookAccess.pages().stream().map(Component::getString).toList()));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || Modules.dormant()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY < 0) {
            pageForward();
        } else {
            pageBack();
        }
        return true;
    }
}
