package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.util.UnmoddedText;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    // The client works out the result slot itself by comparing the typed name with the words of
    // the item's name. The server makes the same comparison without any mod's words. A different
    // read here would show a renamed item the server never offers.
    @WrapOperation(method = "createResult()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;"))
    private Component readName(ItemStack stack, Operation<Component> original) {
        return UnmoddedText.anvilName(stack);
    }
}
