package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class AutoReconnect extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds to wait before reconnecting.", 5, 0, 60, 0.1, "s").min(0);
    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Adds a reconnect button and a switch to the disconnected screen.", true);
    private final BoolSetting loadingButtons = new BoolSetting("Loading buttons",
        "Adds disconnect and reconnect buttons to the loading screen for a join that never finishes.", true);

    // The last server joined. Kept even whilst the module is off. Switched on
    // after a disconnect it still knows where to go.
    private static ServerData lastServer;

    private int countdown = -1;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.MISC);
        addSettings(delay, buttons, loadingButtons);
        searchTags("rejoin", "reconnect", "stuck loading", "loading terrain", "cancel loading");
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

    public boolean showsLoadingButtons() {
        return loadingButtons.isOn();
    }

    // Seconds left rounded to one place. Below zero whilst nothing is counting.
    public double secondsLeft() {
        return countdown < 0 ? -1 : Math.round(countdown / 2f) / 10.0;
    }

    public static boolean canReconnect() {
        return lastServer != null;
    }

    // True whilst you play on a server there is a way back to.
    public static boolean canRejoin() {
        return canReconnect() && !mc.isLocalServer();
    }

    // Leaves the world the way the pause menu does. A single player world is saved first.
    public static void leave() {
        mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
    }

    // Leaves the server and joins it again straight away.
    public static void rejoin() {
        leave();
        connect();
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
            countdown = (int) Math.round(delay.getValue() * SharedConstants.TICKS_PER_SECOND);
        }
        countdown--;
        if (countdown > 0) {
            return;
        }

        countdown = -1;
        connect();
    }

    // Cancelling the connection goes back to the screen that was open.
    private static void connect() {
        Screen parent = mc.gui.screen();
        if (lastServer == null || parent == null) {
            return;
        }
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
    }

    @Override
    protected void onDisable() {
        countdown = -1;
    }
}
