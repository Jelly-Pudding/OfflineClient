package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Connection.class)
public abstract class ConnectionMixin extends SimpleChannelInboundHandler<Packet<?>> {

    // Bundles carry several packets in one wrapper.
    @ModifyVariable(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"),
        argsOnly = true)
    private Packet<?> unpackBundle(Packet<?> packet) {
        if (!(packet instanceof ClientboundBundlePacket bundle)) {
            return packet;
        }
        List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            PacketReceiveEvent event = new PacketReceiveEvent(sub);
            OfflineClient.INSTANCE.getEventBus().post(event);
            if (event.isCancelled()) {
                changed = true;
            } else {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> result =
                    (Packet<? super ClientGamePacketListener>) event.getPacket();
                changed |= result != sub;
                kept.add(result);
            }
        }
        return changed ? new ClientboundBundlePacket(kept) : packet;
    }

    @WrapOperation(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void wrapHandle(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        // Bundles already had an event each in unpackBundle.
        if (packet instanceof ClientboundBundlePacket) {
            original.call(packet, listener);
            return;
        }
        PacketReceiveEvent event = new PacketReceiveEvent(packet);
        OfflineClient.INSTANCE.getEventBus().post(event);
        if (event.isCancelled()) {
            return;
        }
        original.call(event.getPacket(), listener);
    }

    @WrapMethod(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V")
    private void wrapSend(Packet<?> packet, ChannelFutureListener callback, Operation<Void> original) {
        PacketSendEvent event = new PacketSendEvent(packet);
        OfflineClient.INSTANCE.getEventBus().post(event);
        if (event.isCancelled()) {
            return;
        }
        original.call(event.getPacket(), callback);
    }
}
