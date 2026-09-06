package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Mixin(ItemContainerContents.class)
public abstract class ItemContainerContentsMixin {

    @Shadow
    @Final
    private List<Optional<ItemStackTemplate>> items;

    // The vanilla item lines make way for the preview grid or the compact list.
    @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
    private void onAddToTooltip(Item.TooltipContext context, Consumer<Component> out, TooltipFlag flag,
                                DataComponentGetter components, CallbackInfo ci) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips == null) {
            return;
        }
        if (tooltips.skipsContainerLines()) {
            ci.cancel();
        } else if (tooltips.compactsContainerLines()) {
            tooltips.compactLines(items, out);
            ci.cancel();
        }
    }
}
