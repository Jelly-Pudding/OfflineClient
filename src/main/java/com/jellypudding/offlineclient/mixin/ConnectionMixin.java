package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mixin(Connection.class)
public abstract class ConnectionMixin extends SimpleChannelInboundHandler<Packet<?>> {

    private final ConcurrentLinkedQueue<PacketSendEvent> offlineclient$events = new ConcurrentLinkedQueue<>();

    /**
     * Bundles carry several packets in one wrapper. Each one gets its own
     * event and cancelled ones are dropped from the bundle.
     */
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
                kept.add(sub);
            }
        }
        return changed ? new ClientboundBundlePacket(kept) : packet;
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
            ordinal = 0),
        cancellable = true)
    private void onChannelRead0(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundBundlePacket) {
            return;
        }
        PacketReceiveEvent event = new PacketReceiveEvent(packet);
        OfflineClient.INSTANCE.getEventBus().post(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"))
    private Packet<?> modifyPacket(Packet<?> packet) {
        PacketSendEvent event = new PacketSendEvent(packet);
        offlineclient$events.add(event);
        OfflineClient.INSTANCE.getEventBus().post(event);
        return event.getPacket();
    }

    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onSend(Packet<?> packet, @Nullable ChannelFutureListener callback, CallbackInfo ci) {
        PacketSendEvent event = offlineclient$getEvent(packet);
        if (event == null) {
            return;
        }
        if (event.isCancelled()) {
            ci.cancel();
        }
        offlineclient$events.remove(event);
    }

    private PacketSendEvent offlineclient$getEvent(Packet<?> packet) {
        for (PacketSendEvent event : offlineclient$events) {
            if (event.getPacket() == packet) {
                return event;
            }
        }
        return null;
    }
}
