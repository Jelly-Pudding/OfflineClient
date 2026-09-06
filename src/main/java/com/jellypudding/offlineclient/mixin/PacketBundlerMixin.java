package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A bundle carrying more than four thousand packets is refused by vanilla.
@Mixin(targets = "net.minecraft.network.protocol.BundlerInfo$1$1")
public abstract class PacketBundlerMixin {

    @ModifyExpressionValue(method = "addPacket", at = @At(value = "CONSTANT", args = "intValue=4096"))
    private int liftBundleLimit(int vanilla) {
        AntiPacketKick module = Modules.get(AntiPacketKick.class);
        return module != null && module.acceptsHugePackets() ? Integer.MAX_VALUE : vanilla;
    }
}
