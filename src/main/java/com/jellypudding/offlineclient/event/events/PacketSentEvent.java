package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.network.protocol.Packet;

// Fired right behind the write of a packet that got past every module. These come in the
// order the packets left. A packet sent from here follows that one straight on the wire.
public final class PacketSentEvent extends UncancellableEvent {

    private final Packet<?> packet;

    public PacketSentEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }
}
