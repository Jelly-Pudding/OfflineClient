package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

// Whilst it holds a dropped connection the game is never told that the connection ended. The
// world stays as the last packet left it. Letting go hands the game its own ending and the
// disconnected screen opens with the reason the way it always does.
public final class Linger extends Module {

    // Read by whatever thread sends a packet.
    private volatile Connection held;
    // A connection you are leaving on purpose. It is never held.
    private Connection leaving;

    public Linger() {
        super("Linger", "Keeps you in the world as it was when the connection drops and says why in chat.",
            Category.MISC);
        searchTags("silent disconnect", "stay in world", "frozen world", "kick screen", "disconnected screen");
    }

    // A world held when the module goes off is ended by the game as it would have been.
    @Override
    protected void onDisable() {
        held = null;
        leaving = null;
    }

    public static boolean holding() {
        Linger linger = Modules.get(Linger.class);
        if (linger == null) {
            return false;
        }
        linger.forgetGone();
        return linger.held != null;
    }

    // True when a world was being held.
    public static boolean letGo() {
        Linger linger = Modules.get(Linger.class);
        return linger != null && linger.release();
    }

    // Called by every way the client leaves and by a server dialog the player leaves through.
    public static void expectLeave() {
        Linger linger = Modules.active(Linger.class);
        ClientPacketListener game = mc.getConnection();
        if (linger != null && game != null) {
            linger.leaving = game.getConnection();
        }
    }

    // Asked by ConnectionMixin each time the game goes to end a connection. True keeps the game
    // from it. Only the connection the world came over is kept. It is never one to a world of your
    // own or one the server moves you off or one that drops on the loading screen.
    public boolean keeps(Connection connection) {
        if (connection == held) {
            return true;
        }
        ClientPacketListener game = mc.getConnection();
        if (!isEnabled() || connection == leaving || game == null || game.getConnection() != connection
            || game.isTransferring || connection.isMemoryConnection() || connection.isConnected()
            || mc.level == null || mc.player == null || mc.gui.screen() instanceof LevelLoadingScreen) {
            return false;
        }
        held = connection;
        announce(connection.getDisconnectionDetails());
        return true;
    }

    // Asked by ConnectionMixin. The game would queue a packet sent on the held connection and
    // nothing would ever empty that queue.
    public boolean drops(Connection connection) {
        return connection == held;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        forgetGone();
    }

    // A world left through the pause menu or the death screen takes its connection with it and the
    // game never says that the connection ended.
    private void forgetGone() {
        ClientPacketListener game = mc.getConnection();
        Connection current = game == null ? null : game.getConnection();
        if (held != current) {
            held = null;
        }
        if (leaving != current) {
            leaving = null;
        }
    }

    private boolean release() {
        forgetGone();
        Connection was = held;
        if (was == null) {
            return false;
        }
        leaving = was;
        held = null;
        return true;
    }

    // A kick reason can be as long as the server likes. The webhook hears of the drop here because
    // the screen that tells it may be a long time coming.
    private static void announce(DisconnectionDetails details) {
        Component reason = details == null ? Component.translatable("multiplayer.disconnect.generic")
            : details.reason();
        double rejoin = AutoReconnect.rejoinsIn();
        ChatUtil.message("§cThe connection dropped. §f"
            + reason.getString(ChatUtil.LONGEST_SHOWN).replace('\n', ' ').trim()
            + " §7You are still in the world as it was. Type §f"
            + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "disconnect§7 to leave it."
            + (rejoin < 0 ? "" : " AutoReconnect takes you back in " + ChatUtil.duration(rejoin) + "."));
        Webhook webhook = Modules.active(Webhook.class);
        if (webhook != null) {
            webhook.dropped(reason);
        }
    }
}
