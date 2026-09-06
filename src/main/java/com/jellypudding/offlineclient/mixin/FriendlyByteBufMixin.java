package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// The two megabyte tracker refuses an oversized tag and kills the connection.
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin {

    @ModifyArg(method = "readNbt(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/nbt/CompoundTag;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;readNbt(Lio/netty/buffer/ByteBuf;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/Tag;"))
    private static NbtAccounter liftTagLimit(NbtAccounter vanilla) {
        AntiPacketKick module = Modules.get(AntiPacketKick.class);
        return module != null && module.acceptsHugePackets() ? NbtAccounter.unlimitedHeap() : vanilla;
    }
}
