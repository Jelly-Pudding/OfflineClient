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

    // Every tooltip set from a stack starts here. A slot's tooltip is set from its lines and its
    // stack comes through AbstractContainerScreenMixin.
    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
        at = @At("HEAD"))
    private void onSetTooltipForItem(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null) {
            tooltips.setHovered(stack);
        }
    }

    // Every slot and the hotbar and the client's own previews draw an item's count and bar here.
    @Inject(
        method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("HEAD"))
    private void onItemDecorations(Font font, ItemStack stack, int x, int y, String countText, CallbackInfo ci) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null) {
            tooltips.drawContentsIcon((GuiGraphicsExtractor) (Object) this, font, stack, x, y);
        }
    }

    // Every tooltip ends in this overload. Some callers hand in an immutable list and the module builds
    // a fresh one.
    @ModifyVariable(method = "setTooltipForNextFrameInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;II"
        + "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;"
        + "Lnet/minecraft/resources/Identifier;ZZ)V",
        at = @At("HEAD"), index = 2)
    private List<ClientTooltipComponent> addPreview(List<ClientTooltipComponent> lines) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        return tooltips == null ? lines : tooltips.decorate(lines);
    }
}
