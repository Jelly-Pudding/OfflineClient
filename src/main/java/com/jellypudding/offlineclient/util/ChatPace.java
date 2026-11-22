package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.util.Util;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.function.UnaryOperator;

// The lines the client sends go out through here at the pace of whatever sent them. A server
// keeps two counts against spam. One is for chat lines and one for commands. A line adds twenty
// to its count and every server tick takes one back off. The server drops a player whose count
// reaches the limit. Both counts are mirrored here from every line that leaves whoever sent it
// and a pace holds a line back once it would take a count past where that pace stops.
public enum ChatPace {

    // A line the player typed or clicked. It is never held and every other pace leaves room for it.
    PLAYER,
    // A macro line or a line from a module whose whole job is sending lines at the rate the player
    // chose. One more line has to keep its count at or under the ceiling with the round trip kept
    // in hand.
    MACRO,
    // A line a module sends on its own for the player such as a reply or a rule of Triggers. These
    // go four lines in a row and one a second after that. A rule that sends several lines at once
    // such as a macro may take the count further.
    RULE,
    // A line that gets the player out of danger or past a login prompt. It goes further than a rule
    // and never waits behind one.
    ESCAPE;

    // What one line adds and the count a server drops the player at unless its owner changed it.
    static final int LINE = 20;
    static final int KICK = LINE * 10;

    // Half the limit is kept in hand for a macro. Lines bunch up when the way to the server stalls
    // and the player or another part of the client may send lines beside a macro.
    private static final int CEILING = KICK / 2;

    // A rule stops lower still. The rest is left for what the player types and for a server set to
    // kick sooner. Six lines are the most one rule sends in one go.
    private static final int RULE_STOP = KICK * 2 / 5;
    private static final int BATCH_STOP = KICK * 3 / 5;

    // An escape may go further. It must not wait behind a rule or a macro.
    private static final int ESCAPE_STOP = KICK * 4 / 5;

    // The mirror counts a line as it leaves and the server only once it lands. Ticks the server
    // spends with nothing to take off in between are lost to it and the round trip is kept in
    // hand as well. A ping past this is taken for this long.
    private static final int LONGEST_TRIP = CEILING - LINE;

    // The most ticks one time packet may stand for. A longer jump is another world's clock.
    private static final long LONGEST_BEAT = 10L * SharedConstants.TICKS_PER_SECOND;

    // A time packet may run this many ticks late before any of what it takes off is lost.
    private static final double MOST_SPARE = 1.5 * SharedConstants.TICKS_PER_SECOND;

    private static final double MILLIS_PER_TICK = SharedConstants.MILLIS_PER_TICK;

    private static final long TELL_GAP_MS = 5000;

    private static long toldAt;

    // Fed by every line that leaves and by the time the server sends. The event bus holds it from
    // the very first line.
    public static final Mirror MIRROR = new Mirror();

    // Sends the line as the player at this pace. A line starting with a slash runs as a server
    // command the way the chat screen runs it. False when it was held back and is worth another
    // try. A blank line and a dormant client send nothing and have nothing to wait for.
    public boolean say(String line) {
        return say(line, UnaryOperator.identity());
    }

    // The same with the words after any slash passed through edit on the way out. The line as
    // given decides between chat and a command and nothing edit writes can change that.
    public boolean say(String line, UnaryOperator<String> edit) {
        boolean command = line.startsWith("/");
        return send(command, command ? line.substring(1) : line, edit);
    }

    // The words as a line of chat whatever they start with. Chat never runs as a command.
    public boolean chat(String words) {
        return send(false, words, UnaryOperator.identity());
    }

    public boolean fits(int chatLines, int commandLines) {
        return MIRROR.fits(this, chatLines, commandLines);
    }

    // A line for the client itself costs nothing and a blank one is never sent.
    public boolean affords(Collection<String> lines) {
        int[] counted = counted(lines);
        return fits(counted[0], counted[1]);
    }

    // False for more lines at once than this pace lets go however long they wait.
    public boolean everAffords(Collection<String> lines) {
        int[] counted = counted(lines);
        return fits(new Counts(0), counted[0], counted[1], 0, 0);
    }

    public static void tellHeld(String owner) {
        long now = Util.getMillis();
        if (now - toldAt >= TELL_GAP_MS) {
            toldAt = now;
            ChatUtil.error(owner + " held a line back. The server kicks for too many lines in a row.");
        }
    }

    // Nothing in here reads a clock or the game.
    boolean fits(Counts counts, int chatLines, int commandLines, long now, int tripTicks) {
        return switch (this) {
            case PLAYER -> true;
            case MACRO -> {
                int ceiling = CEILING - Math.clamp(tripTicks, 0, LONGEST_TRIP);
                yield counts.fits(chatLines, commandLines, ceiling, ceiling, now);
            }
            case RULE -> counts.fits(chatLines, commandLines, RULE_STOP, BATCH_STOP, now);
            case ESCAPE -> counts.fits(chatLines, commandLines, ESCAPE_STOP, ESCAPE_STOP, now);
        };
    }

    // A server that gives no ping for the player is taken for the slowest.
    static int tripTicks(int pingMillis) {
        return pingMillis > 0 ? Math.round(pingMillis / (float) MILLIS_PER_TICK) : LONGEST_TRIP;
    }

    private boolean send(boolean command, String typed, UnaryOperator<String> edit) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null || typed.isBlank() || Modules.dormant()) {
            return true;
        }
        if (!fits(command ? 0 : 1, command ? 1 : 0)) {
            return false;
        }
        String words = edit.apply(typed);
        if (words.isBlank()) {
            return true;
        }
        if (command) {
            connection.sendCommand(words);
        } else {
            connection.sendChat(words);
        }
        return true;
    }

    private static int[] counted(Collection<String> lines) {
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        int[] counted = new int[2];
        for (String line : lines) {
            if (!line.isBlank() && !line.startsWith(prefix)) {
                counted[line.startsWith("/") ? 1 : 0]++;
            }
        }
        return counted;
    }

    // The sums alone. Every time is handed in and nothing in here reads a clock or the game.
    // The ticks are read off the time the server sends every twenty of them. A server that has
    // stalled sends none and nothing comes off. One catching up ticks many times in a moment
    // and lines sent on the strength of that would land after it has slowed again. No more
    // comes off than the clock on the wall has let through.
    static final class Counts {

        private double chat;
        private double commands;

        private long serverTime;
        private boolean timeKnown;

        // Whether this server has sent the time at all. One that never does keeps no such
        // counts and the clock on the wall stands in.
        private boolean timed;

        // Ticks the clock on the wall has let through that no time packet has claimed yet.
        private double spare;
        private long readAt;

        Counts(long now) {
            readAt = now;
        }

        void add(boolean command, long now) {
            pass(now);
            if (command) {
                commands += LINE;
            } else {
                chat += LINE;
            }
        }

        // The first line has to keep its count at or under the stop and the last at or under the
        // reach. A count no line goes to is left out.
        boolean fits(int chatLines, int commandLines, int stop, int reach, long now) {
            pass(now);
            return fits(chat, chatLines, stop, reach) && fits(commands, commandLines, stop, reach);
        }

        void hearTime(long gameTime, long now) {
            pass(now);
            long passed = gameTime - serverTime;
            if (timeKnown && passed > 0 && passed <= LONGEST_BEAT) {
                double taken = Math.min(passed, spare);
                spare -= taken;
                take(taken);
            }
            serverTime = gameTime;
            timeKnown = true;
            timed = true;
        }

        // Each world of a server may keep a clock of its own.
        void changeWorld() {
            timeKnown = false;
        }

        private static boolean fits(double count, int lines, int stop, int reach) {
            return lines == 0 || count + LINE <= stop && count + lines * LINE <= reach;
        }

        private void pass(long now) {
            double ticks = Math.max(0, now - readAt) / MILLIS_PER_TICK;
            readAt = now;
            if (timed) {
                spare = Math.min(spare + ticks, MOST_SPARE);
            } else {
                take(ticks);
            }
        }

        private void take(double ticks) {
            chat = Math.max(0, chat - ticks);
            commands = Math.max(0, commands - ticks);
        }
    }

    // The counts of the connection in use. Lines are sent and the time comes in on more than one
    // thread.
    public static final class Mirror {

        private Counts counts = new Counts(Util.getMillis());

        // The connection the counts belong to. A fresh join starts both from nought.
        private WeakReference<ClientPacketListener> counted = new WeakReference<>(null);

        private Mirror() {
        }

        @Subscribe
        private void onPacketSent(PacketSentEvent event) {
            Packet<?> packet = event.getPacket();
            boolean command = packet instanceof ServerboundChatCommandPacket
                || packet instanceof ServerboundChatCommandSignedPacket;
            if (command || packet instanceof ServerboundChatPacket) {
                synchronized (this) {
                    current().add(command, Util.getMillis());
                }
            }
        }

        @Subscribe
        private void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacket() instanceof ClientboundSetTimePacket time) {
                synchronized (this) {
                    current().hearTime(time.gameTime(), Util.getMillis());
                }
            } else if (event.getPacket() instanceof ClientboundRespawnPacket) {
                synchronized (this) {
                    current().changeWorld();
                }
            }
        }

        // A world the player hosts keeps no count against its owner and a macro there is never held.
        // Only a macro keeps the round trip in hand.
        private synchronized boolean fits(ChatPace pace, int chatLines, int commandLines) {
            if (pace == MACRO && OfflineClient.MC.hasSingleplayerServer()) {
                return true;
            }
            int trip = pace == MACRO ? tripTicks(ServerInfo.ping()) : 0;
            return pace.fits(current(), chatLines, commandLines, Util.getMillis(), trip);
        }

        private Counts current() {
            ClientPacketListener connection = OfflineClient.MC.getConnection();
            if (connection != counted.get()) {
                counted = new WeakReference<>(connection);
                counts = new Counts(Util.getMillis());
            }
            return counts;
        }
    }
}
