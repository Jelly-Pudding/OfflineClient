package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Vanilla drops the connection when an uncompressed packet is over eight megabytes.
@Mixin(CompressionDecoder.class)
public abstract class CompressionDecoderMixin {

    @ModifyExpressionValue(method = "decode", at = @At(value = "CONSTANT", args = "intValue=8388608"))
    private int liftSizeLimit(int vanilla) {
        AntiPacketKick module = Modules.get(AntiPacketKick.class);
        return module != null && module.acceptsHugePackets() ? Integer.MAX_VALUE : vanilla;
    }
}
