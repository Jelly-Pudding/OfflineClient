package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.util.UnmoddedText;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {

    // The name box fills with the words of the item's name and a custom name goes straight back
    // to the server as a rename. Both reads have to agree or every item would look renamed.
    @WrapOperation(
        method = {
            "slotChanged(Lnet/minecraft/world/inventory/AbstractContainerMenu;ILnet/minecraft/world/item/ItemStack;)V",
            "onNameChanged(Ljava/lang/String;)V"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;"))
    private Component readName(ItemStack stack, Operation<Component> original) {
        return UnmoddedText.anvilName(stack);
    }
}
