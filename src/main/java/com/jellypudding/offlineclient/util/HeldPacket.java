package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import net.minecraft.network.protocol.Packet;

// One outgoing packet kept back whilst the server is set up for it. Sending it later
// runs back through the send handler which has to let it by whilst releasing() is true.
public final class HeldPacket<P extends Packet<?>> {

    private P packet;
    private boolean releasing;

    public void hold(PacketSendEvent event, P held) {
        packet = held;
        event.cancel();
    }

    public boolean releasing() {
        return releasing;
    }

    // Sends the held packet on. Nothing happens when none is held.
    public void release() {
        P out = packet;
        packet = null;
        if (out == null || OfflineClient.MC.player == null) {
            return;
        }
        releasing = true;
        try {
            OfflineClient.MC.player.connection.send(out);
        } finally {
            releasing = false;
        }
    }

    // Throws the held packet away. It never reaches the server.
    public void discard() {
        packet = null;
    }
}
