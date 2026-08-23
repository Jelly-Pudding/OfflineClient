package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.network.protocol.Packet;

// Fired on the network thread before the game handles the packet.
public final class PacketReceiveEvent extends Event {

    private Packet<?> packet;

    public PacketReceiveEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    public void setPacket(Packet<?> packet) {
        this.packet = packet;
    }
}
