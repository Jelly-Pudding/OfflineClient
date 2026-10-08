package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The game refuses a server on Mojang's blocked list before it ever connects. Joining and the
// server list pings both resolve here.
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {

    @ModifyExpressionValue(
        method = "resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerNameResolver;addressCheck:Lnet/minecraft/client/multiplayer/resolver/AddressCheck;",
            opcode = Opcodes.GETFIELD))
    private AddressCheck onAddressCheck(AddressCheck original) {
        ServerSpoof spoof = Modules.get(ServerSpoof.class);
        return spoof == null ? original : spoof.addressCheck(original);
    }
}
