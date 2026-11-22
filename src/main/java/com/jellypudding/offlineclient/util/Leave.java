package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.modules.misc.Linger;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// A plain quit shows its words on the disconnected screen. A way that gets you kicked leaves that
// screen to the server and keeps the words to go under the kick text. A way the server lets pass
// ends in a plain quit with the reason under the words.
public enum Leave {

    QUIT,
    // The section sign is refused by every server before anything else is read.
    CHAT,
    // The server kicks a player who hits themselves.
    HURT,
    // A position that is not a number is the first thing the server checks a move for.
    MOVE;

    // The kick comes within a round trip. A server that has not kicked by then never will.
    private static final int KICK_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;

    // A disconnect much later than this has some other cause than the kick asked for.
    private static final long NOTE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private static Component note;
    private static long notedAt;

    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static List<String> words() {
        return Arrays.stream(values()).map(Leave::word).toList();
    }

    public static Leave named(String word) {
        return Arrays.stream(values()).filter(way -> way.word().equalsIgnoreCase(word)).findFirst().orElse(null);
    }

    // A world Linger holds has no connection left to close and is let go instead.
    public void go(String words) {
        ClientPacketListener game = OfflineClient.MC.getConnection();
        LocalPlayer player = OfflineClient.MC.player;
        if (game == null || player == null || Linger.letGo()) {
            return;
        }
        Linger.expectLeave();
        Connection connection = game.getConnection();
        String passes = passesBecause(player);
        if (this == QUIT || passes != null) {
            quit(connection, words, passes);
            return;
        }
        note = Component.literal("\n\n§b" + words);
        notedAt = System.currentTimeMillis();
        switch (this) {
            case CHAT -> game.sendChat("§");
            case HURT -> game.send(new ServerboundAttackPacket(player.getId()));
            default -> game.send(new ServerboundMovePlayerPacket.Pos(Double.NaN, Double.NaN, Double.NaN,
                player.onGround(), player.horizontalCollision));
        }
        OfflineClient.INSTANCE.getEventBus().register(new Fallback(connection, words));
    }

    public static void toMenu() {
        OfflineClient.MC.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
    }

    // Why the server would let this way pass without a kick. Null when it kicks. Only a hit has
    // conditions and every server ignores one from a spectator. Paper also ignores a player who
    // is dead or asleep and kicks a hit on yourself before it looks at the weapon. A vanilla
    // server looks at the weapon first and ignores a hit with a spear.
    private String passesBecause(LocalPlayer player) {
        if (this == QUIT) {
            return null;
        }
        if (OfflineClient.MC.isLocalServer()) {
            return "Your own world has nobody to kick you.";
        }
        if (this != HURT) {
            return null;
        }
        if (player.isSpectator()) {
            return "The server ignores a hit from a spectator.";
        }
        if (ServerInfo.runsPaper()) {
            return player.isDeadOrDying() || player.isSleeping()
                ? "The server ignores a hit from a player who is dead or asleep." : null;
        }
        return ItemUtil.landsPlainHit(player.getMainHandItem())
            ? null : "The server ignores a hit from a player holding a spear.";
    }

    private static void quit(Connection connection, String words, String reason) {
        note = null;
        connection.disconnect(ChatUtil.tagged(Component.literal("§f" + words
            + (reason == null ? "" : "\n§7" + reason))));
    }

    // Null once read and for a disconnect long after.
    public static Component takeNote() {
        Component fresh = System.currentTimeMillis() - notedAt <= NOTE_MILLIS ? note : null;
        note = null;
        return fresh;
    }

    // Quits plainly once a kick asked for has had its time and the connection is still open.
    private static final class Fallback {

        private final Connection connection;
        private final String words;
        private int ticksLeft = KICK_TICKS;

        private Fallback(Connection connection, String words) {
            this.connection = connection;
            this.words = words;
        }

        @Subscribe
        private void onClientTick(ClientTickEvent event) {
            if (connection.isConnected() && --ticksLeft > 0) {
                return;
            }
            OfflineClient.INSTANCE.getEventBus().unregister(this);
            if (connection.isConnected()) {
                quit(connection, words, "The server did not kick you.");
            }
        }
    }
}
