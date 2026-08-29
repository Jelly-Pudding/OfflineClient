package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    // The paint goes down first so the item sits on top of it.
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void onExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                               CallbackInfo ci) {
        ItemHighlight highlight = Modules.get(ItemHighlight.class);
        if (highlight == null) {
            return;
        }
        int color = highlight.colorFor(slot.getItem());
        if (color != 0) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }
}
