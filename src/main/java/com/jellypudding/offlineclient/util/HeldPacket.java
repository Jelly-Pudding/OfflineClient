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

    public void release() {
        release(() -> { });
    }

    // First runs inside the same guard just before the packet goes out. Nothing runs
    // when no packet is held.
    public void release(Runnable first) {
        P out = packet;
        packet = null;
        if (out == null || OfflineClient.MC.player == null) {
            return;
        }
        releasing = true;
        try {
            first.run();
            OfflineClient.MC.player.connection.send(out);
        } finally {
            releasing = false;
        }
    }

    public void drop() {
        packet = null;
    }
}
