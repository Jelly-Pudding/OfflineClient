package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.events.PacketSendEvent;

// A sneak only the server is told of whilst the client stands up. Each owner holds its own. Two
// at once agree because each keeps every input packet crouched on its way out whilst it wants to.
public final class ServerSneak {

    // A respawn or a dimension change wipes what the server was told. A fresh body is told again.
    private final LifeWatch body = new LifeWatch();
    // Read on whatever thread sends a packet.
    private volatile boolean wanted;

    // Tells the server whenever the wish changes.
    public void want(boolean sneak) {
        boolean freshBody = body.changed();
        if (sneak != wanted || sneak && freshBody) {
            wanted = sneak;
            InputUtil.sendShift(sneak);
        }
    }

    // The owner hands each packet on its way out to this.
    public void keep(PacketSendEvent event) {
        if (wanted) {
            InputUtil.keepShift(event);
        }
    }

    public void release() {
        want(false);
    }
}
