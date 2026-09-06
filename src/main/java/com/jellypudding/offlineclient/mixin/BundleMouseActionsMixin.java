package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.BundleMouseActions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Vanilla only lets the scroll reach the few items a bundle draws.
@Mixin(BundleMouseActions.class)
public abstract class BundleMouseActionsMixin {

    private static final String SHOWN =
        "Lnet/minecraft/world/item/BundleItem;getNumberOfItemsToShow(Lnet/minecraft/world/item/ItemStack;)I";

    @ModifyExpressionValue(method = "toggleSelectedBundleItem",
        at = @At(value = "INVOKE", target = SHOWN))
    private int uncapToggle(int shown, ItemStack bundle, int slot, int selected) {
        return offlineclient$reach(shown, bundle);
    }

    @ModifyExpressionValue(method = "onMouseScrolled",
        at = @At(value = "INVOKE", target = SHOWN))
    private int uncapScroll(int shown, double scrollX, double scrollY, int slot, ItemStack bundle) {
        return offlineclient$reach(shown, bundle);
    }

    private static int offlineclient$reach(int shown, ItemStack bundle) {
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        if (tweaks == null || !tweaks.uncapsBundles()) {
            return shown;
        }
        return bundle.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).size();
    }
}
