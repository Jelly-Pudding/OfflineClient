package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.network.protocol.Packet;

// Fired once per send. A replaced packet is the one that goes out.
public final class PacketSendEvent extends Event {

    private Packet<?> packet;

    public PacketSendEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    public void setPacket(Packet<?> packet) {
        this.packet = packet;
    }
}
