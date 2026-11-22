package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.Util;

// How long the player has left the keyboard and the mouse alone. A key or a button going down
// or coming up counts as a touch and any movement of the mouse does too. A key a module holds
// down for you does not.
public final class IdleTime {

    public static final IdleTime INSTANCE = new IdleTime();

    private long touchedAt = Util.getMillis();
    private double mouseX;
    private double mouseY;

    private IdleTime() {
    }

    public long millis() {
        return Util.getMillis() - touchedAt;
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        touchedAt = Util.getMillis();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        MouseHandler mouse = OfflineClient.MC.mouseHandler;
        if (mouse.xpos() != mouseX || mouse.ypos() != mouseY) {
            mouseX = mouse.xpos();
            mouseY = mouse.ypos();
            touchedAt = Util.getMillis();
        }
    }
}
