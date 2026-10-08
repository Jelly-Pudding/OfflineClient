package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.config.NameStore;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

// The thread the connect screen starts for every join. Its hello packet tells the server who
// you are. An offline server takes the name and works out the same id from it.
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ServerConnectorMixin {

    @WrapOperation(method = "run",
        at = @At(value = "NEW", target = "Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;"))
    private ServerboundHelloPacket joinAs(String name, UUID id, Operation<ServerboundHelloPacket> original) {
        String chosen = NameStore.get().inUse();
        return chosen == null ? original.call(name, id)
            : original.call(chosen, UUIDUtil.createOfflinePlayerUUID(chosen));
    }
}
