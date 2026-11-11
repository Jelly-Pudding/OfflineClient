package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.LastServer;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class AutoReconnect extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds to wait before reconnecting.", 5, 0, 60, 0.1, "s");
    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Adds a reconnect button and a switch to the disconnected screen.", true);
    private final BoolSetting loadingButtons = new BoolSetting("Loading buttons",
        "Adds disconnect and reconnect buttons to the loading screen for a join that never finishes.", true);
    private final BoolSetting nameButton = new BoolSetting("Name button",
        "Adds a button to the disconnected screen that rejoins under your next saved name.", true);
    private final BoolSetting connectLog = new BoolSetting("Connect log",
        "Shows the newest game log lines on the connecting and disconnected screens.", false);

    // The last server joined. Kept even whilst the module is off and saved for the next game.
    // Switched on after a disconnect it still knows where to go.
    private static ServerData lastServer = LastServer.read();

    // Every join this module has started by itself after a disconnect. A button press never
    // counts. A flight the connection cut short only goes on after one.
    private static int joinsMade;

    private int countdown = -1;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.MISC);
        addSettings(delay, buttons, loadingButtons, nameButton, connectLog);
        searchTags("rejoin", "reconnect", "stuck loading", "loading terrain", "cancel loading", "alt name",
            "connection log");
    }

    // Called from the connect screen whatever the module is doing.
    public static void remember(ServerData server) {
        if (server != null && server != lastServer) {
            lastServer = server;
            LastServer.write(server);
        }
    }

    public boolean showsButtons() {
        return buttons.isOn();
    }

    public boolean showsLoadingButtons() {
        return loadingButtons.isOn();
    }

    public boolean showsNameButton() {
        return nameButton.isOn();
    }

    public boolean showsConnectLog() {
        return connectLog.isOn();
    }

    // Seconds left rounded to one place. Below zero whilst nothing is counting.
    public double secondsLeft() {
        return countdown < 0 ? -1 : Math.round(countdown / 2f) / 10.0;
    }

    public static boolean canReconnect() {
        return lastServer != null;
    }

    // The address of the last server joined. Null before the first join and after a realm whose
    // address only lets you in through the realm menu.
    public static String lastAddress() {
        return lastServer == null || lastServer.isRealm() ? null : lastServer.ip;
    }

    // Seconds before a dropped connection is joined again. Below zero whilst it will not be.
    public static double rejoinsIn() {
        AutoReconnect module = Modules.active(AutoReconnect.class);
        return module == null || lastServer == null || NameStore.get().checkedName() != null ? -1
            : module.delay.getValue();
    }

    public static int joinsMade() {
        return joinsMade;
    }

    // True whilst you play on a server there is a way back to.
    public static boolean canRejoin() {
        return canReconnect() && !mc.isLocalServer();
    }

    // Leaves the server and joins it again straight away.
    public static void rejoin() {
        Leave.toMenu();
        connect();
    }

    public void reconnectNow() {
        countdown = -1;
        connect();
    }

    // Joins again under another name. The name stays in use for later joins.
    public void reconnectAs(String name) {
        NameStore.get().use(name);
        reconnectNow();
    }

    // The button on the screen flips the module and starts the wait again.
    public void toggleFromScreen() {
        countdown = -1;
        toggle();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        // A world Linger holds has dropped. The wait runs there as it does on the screen.
        boolean lingering = Linger.holding();
        if (mc.level != null && mc.getCurrentServer() != null && !lingering) {
            remember(mc.getCurrentServer());
            countdown = -1;
            return;
        }

        // A server that turned the saved name away turns it away again.
        if (!(lingering || mc.gui.screen() instanceof DisconnectedScreen) || lastServer == null
            || NameStore.get().checkedName() != null) {
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
        // The disconnected screen opens within a tick of letting go and the join starts from it.
        if (Linger.letGo()) {
            countdown = 0;
            return;
        }

        countdown = -1;
        if (connect()) {
            joinsMade++;
        }
    }

    // Cancelling the connection goes back to the screen that was open. False when no join started.
    private static boolean connect() {
        Screen parent = mc.gui.screen();
        if (lastServer == null || parent == null) {
            return false;
        }
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
        return true;
    }

    @Override
    protected void onDisable() {
        countdown = -1;
    }
}
