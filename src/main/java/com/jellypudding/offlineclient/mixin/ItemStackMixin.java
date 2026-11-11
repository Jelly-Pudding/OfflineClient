package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiCrash;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    // The tooltip and the name over the hotbar and the tag on an item frame all ask here.
    @ModifyReturnValue(method = "getHoverName()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"))
    private Component cutLongName(Component name) {
        AntiCrash guard = Modules.active(AntiCrash.class);
        return guard == null ? name : guard.shortName((ItemStack) (Object) this, name);
    }
}
