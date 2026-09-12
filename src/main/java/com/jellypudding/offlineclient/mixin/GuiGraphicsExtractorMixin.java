package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public class GuiGraphicsExtractorMixin {

    // The only place the stack behind a tooltip is still known.
    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
        at = @At("HEAD"))
    private void onSetTooltipForItem(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null) {
            tooltips.setHovered(stack);
        }
    }

    // Some callers hand in an immutable list. The module builds a fresh one.
    @ModifyVariable(method = "setTooltipForNextFrameInternal", at = @At("HEAD"), index = 2)
    private List<ClientTooltipComponent> addPreview(List<ClientTooltipComponent> lines) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        return tooltips == null ? lines : tooltips.decorate(lines);
    }
}
