package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {

    // The editor starts with the words of whatever text the server put on the sign. Closing it
    // sends those words back.
    @WrapOperation(
        method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;"
            + "Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    private String readLine(Component line, Operation<String> original) {
        String plain = ServerSpoof.plainWords(line);
        return plain == null ? original.call(line) : plain;
    }
}
