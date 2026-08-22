package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.network.protocol.Packet;

/**
 * Fired when a packet arrives from the server. Cancel to drop the packet.
 */
public final class PacketReceiveEvent extends Event {

    private final Packet<?> packet;

    public PacketReceiveEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }
}
