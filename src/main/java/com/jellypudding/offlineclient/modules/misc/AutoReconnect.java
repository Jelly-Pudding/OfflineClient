package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class AutoReconnect extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds to wait before reconnecting.", 5, 1, 60, 1, "s");

    private ServerData lastServer;
    private int countdown = -1;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.MISC);
        addSettings(delay);
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level != null && mc.getCurrentServer() != null) {
            lastServer = mc.getCurrentServer();
            countdown = -1;
            return;
        }

        if (!(mc.gui.screen() instanceof DisconnectedScreen) || lastServer == null) {
            countdown = -1;
            return;
        }

        if (countdown == -1) {
            countdown = delay.getInt() * 20;
        }
        countdown--;
        if (countdown > 0) {
            return;
        }

        countdown = -1;
        ConnectScreen.startConnecting(mc.gui.screen(), mc,
            ServerAddress.parseString(lastServer.ip), lastServer, false, null);
    }
}
