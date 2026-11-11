package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.modules.misc.Linger;
import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PacketNotice;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.ProxyTunnel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.TimeoutException;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.SkipPacketEncoderException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        PacketUtil.noteConnection((Connection) (Object) this);
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
        // unpackBundle fires an event for each packet inside a bundle.
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

    // A timeout still has to disconnect. Anything else can be thrown away.
    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void onExceptionCaught(ChannelHandlerContext context, Throwable cause, CallbackInfo ci) {
        AntiPacketKick module = Modules.get(AntiPacketKick.class);
        if (module == null || !module.catchesErrors()
            || cause instanceof TimeoutException || cause instanceof SkipPacketEncoderException) {
            return;
        }
        if (module.logsErrors()) {
            PacketNotice.report(cause.getClass().getName(), "Dropped a packet the client could not read. " + cause);
        }
        ci.cancel();
    }

    // Every connection the client opens to a server is set up here. Server list pings are too.
    // The proxy tunnel goes first to dial the proxy in place of the server.
    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void onConfigureSerialization(ChannelPipeline pipeline, PacketFlow flow, boolean local,
                                                 BandwidthDebugMonitor monitor, CallbackInfo ci) {
        Proxy proxy = Modules.active(Proxy.class);
        if (proxy != null && flow == PacketFlow.CLIENTBOUND && !local) {
            pipeline.addFirst(ProxyTunnel.NAME, proxy.tunnel());
        }
    }

    // Whilst Linger keeps you in the world of a dropped connection the game is not told that the
    // connection ended.
    @Inject(method = "handleDisconnection", at = @At("HEAD"), cancellable = true)
    private void onHandleDisconnection(CallbackInfo ci) {
        Linger linger = Modules.get(Linger.class);
        if (linger != null && linger.keeps((Connection) (Object) this)) {
            ci.cancel();
        }
    }

    // Every send of the client ends here. Only the connection Linger keeps drops what it is given.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void onSend(CallbackInfo ci) {
        Linger linger = Modules.get(Linger.class);
        if (linger != null && linger.drops((Connection) (Object) this)) {
            ci.cancel();
        }
    }

    // The handshake of a join names the address you typed. It never passes through send. Both ways
    // into a join come here. A world of your own and a look at the server list do not.
    @ModifyVariable(
        method = {
            "initiateServerboundPlayConnection(Ljava/lang/String;I"
                + "Lnet/minecraft/network/protocol/login/ClientLoginPacketListener;)V",
            "initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;"
                + "Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V"},
        at = @At("HEAD"),
        argsOnly = true)
    private String nameJoinedHost(String hostName) {
        ServerSpoof spoof = Modules.get(ServerSpoof.class);
        Connection joining = (Connection) (Object) this;
        return spoof == null || joining.isMemoryConnection() ? hostName : spoof.joining(joining, hostName);
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
        OfflineClient.INSTANCE.getEventBus().post(new PacketSentEvent(event.getPacket()));
    }
}
