package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class AutoReconnect extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds to wait before reconnecting.", 5, 0, 60, 0.1, "s").min(0).max(600);
    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Adds a reconnect button and a switch to the disconnected screen.", true);

    // The last server joined. Kept even whilst the module is off. Switched on
    // after a disconnect it still knows where to go.
    private static ServerData lastServer;

    private int countdown = -1;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.MISC);
        addSettings(delay, buttons);
        searchTags("rejoin", "reconnect");
    }

    // Called from the connect screen whatever the module is doing.
    public static void remember(ServerData server) {
        if (server != null) {
            lastServer = server;
        }
    }

    public boolean showsButtons() {
        return buttons.isOn();
    }

    // Seconds left rounded to one place. Below zero whilst nothing is counting.
    public double secondsLeft() {
        return countdown < 0 ? -1 : Math.round(countdown / 2f) / 10.0;
    }

    public static boolean canReconnect() {
        return lastServer != null;
    }

    public void reconnectNow() {
        countdown = -1;
        connect();
    }

    // The button on the screen flips the module and starts the wait again.
    public void toggleFromScreen() {
        countdown = -1;
        toggle();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level != null && mc.getCurrentServer() != null) {
            remember(mc.getCurrentServer());
            countdown = -1;
            return;
        }

        if (!(mc.gui.screen() instanceof DisconnectedScreen) || lastServer == null) {
            countdown = -1;
            return;
        }

        if (countdown == -1) {
            countdown = (int) Math.round(delay.getValue() * 20);
        }
        countdown--;
        if (countdown > 0) {
            return;
        }

        countdown = -1;
        connect();
    }

    private void connect() {
        if (lastServer == null || mc.gui.screen() == null) {
            return;
        }
        ConnectScreen.startConnecting(mc.gui.screen(), mc,
            ServerAddress.parseString(lastServer.ip), lastServer, false, null);
    }

    @Override
    protected void onDisable() {
        countdown = -1;
    }
}
