package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.concurrent.atomic.AtomicInteger;

// Opens and closes the glide on the server for every module that wants one. One command does
// both and sent twice it undoes itself. The switch watches the glide the server shows turn
// over and counts every command that leaves. It never has two commands of its own on their
// way. A wish that comes during a wait is kept and sent once the wait is over and the server
// still holds the other state. The player's own flag goes over at once and is held there.
public final class GlideSwitch {

    // Entity defines its shared flags first and they travel as value nought. The glide is bit seven.
    private static final int SHARED_FLAGS = 0;
    private static final int GLIDE_FLAG = 1 << 7;

    // A close cannot be refused. Its answer is given this long before the flag is let go.
    private static final int CLOSE_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;

    // Ticks the slowest answer is taken to need until one has been timed and the most it is
    // ever taken to need. An open that has waited twice the slowest answer and a little
    // more was refused. Longer than the most and a player with no glide is kicked for flying.
    private static final int FIRST_GUESS = 4;
    private static final int SLOWEST = SharedConstants.TICKS_PER_SECOND;
    private static final int SLACK_TICKS = 2;

    // How long a glide the server would not open is left alone.
    private static final int REFUSED_TICKS = SharedConstants.TICKS_PER_SECOND;

    private enum Wait {
        // Nothing is on its way. The flag is the server's to set.
        NONE,
        // Only commands from elsewhere are on their way. The flag is left alone.
        WATCHING,
        // A command or a landing of the switch's own is on its way. The flag is held on the wish.
        HOLDING,
        // The wait ran out. An answer that still comes shows how slow the server is.
        LATE
    }

    // The commands of one wait. Each owes one turn of the glide the server shows.
    private final class Sent {

        // The turn count as the first command left and the turns owed since.
        private int base;
        private int owed;
        // The tick the first command left on and the tick the last one did.
        private int begun;
        private int last;
        private boolean timed;

        private void begin() {
            base = turns.get();
            owed = 0;
            begun = ticks;
            timed = false;
        }

        private void add() {
            owed++;
            last = ticks;
        }

        // The turns that have come back.
        private int seen() {
            return turns.get() - base;
        }

        private boolean answeredBy(int seen) {
            return seen >= owed;
        }

        private boolean alone() {
            return owed == 1;
        }

        // Ticks since the last command left.
        private int waited() {
            return ticks - last;
        }

        // The ticks the first turn took to come or nought when it is not in or was timed before.
        private int firstAnswer(int seen) {
            if (seen == 0 || timed) {
                return 0;
            }
            timed = true;
            return ticks - begun;
        }
    }

    private static GlideSwitch instance;

    // What the server last showed and how often what it shows has turned over. Set on the
    // netty thread.
    private volatile boolean serverGlides;
    private final AtomicInteger turns = new AtomicInteger();
    // A join or a respawn gave a player that is not gliding and owes no answer. A join can
    // be to another server and what the last one taught about its answers goes with it.
    private volatile boolean fresh;
    private volatile boolean joined;

    private Wait wait = Wait.NONE;
    // What the caller last asked for. The flag is held on it through a wait.
    private boolean wish;
    // What the server holds once everything on its way has arrived. Every command turns it over.
    private boolean expected;
    private final Sent sent = new Sent();
    private int slowest = FIRST_GUESS;
    private int refused;
    private int ticks;

    private GlideSwitch() {
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public static synchronized GlideSwitch get() {
        if (instance == null) {
            instance = new GlideSwitch();
        }
        return instance;
    }

    // What the server last showed about the glide.
    public boolean serverGlides() {
        return serverGlides;
    }

    // What the server holds once every command on its way has arrived.
    public boolean glides() {
        return waiting() ? expected : serverGlides;
    }

    // Opens the glide the way the game does on a jump with the flag up before the command
    // leaves. Nothing happens when the game's own rules refuse or the server has just refused.
    public void open() {
        if (refused == 0 && OfflineClient.MC.player.tryToStartFallFlying()) {
            ask(true);
        }
    }

    public void close() {
        set(OfflineClient.MC.player, false);
        ask(false);
    }

    // True when a close asked for this tick reaches the server ahead of whatever is sent
    // after it. False whilst commands on their way leave the glide open and a close has to wait.
    public boolean closesAtOnce() {
        return !waiting() || !expected;
    }

    // A landing shuts the glide on the server by itself. Only the flag comes down here
    // and nothing is sent. The server's own shut is waited for like the answer to a command.
    public void land() {
        set(OfflineClient.MC.player, false);
        wish = false;
        if (glides()) {
            turnOver();
        }
        if (waiting()) {
            wait = Wait.HOLDING;
        }
    }

    private boolean waiting() {
        return wait == Wait.WATCHING || wait == Wait.HOLDING;
    }

    // Whilst a wait runs the wish is only kept. Two commands on their way at once could
    // not be told apart by their answers.
    private void ask(boolean open) {
        wish = open;
        if (!waiting() && serverGlides != open) {
            MovementUtil.sendStartGlide();
        }
        if (waiting()) {
            wait = Wait.HOLDING;
        }
    }

    // One more turn of the server's glide is on its way.
    private void turnOver() {
        if (!waiting()) {
            wait = Wait.WATCHING;
            expected = serverGlides;
            sent.begin();
        }
        expected = !expected;
        sent.add();
    }

    private static void set(LocalPlayer player, boolean gliding) {
        if (player.isFallFlying() == gliding) {
            return;
        }
        if (gliding) {
            player.startFallFlying();
        } else {
            player.stopFallFlying();
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        // A join or a respawn that keeps no entity data gives you a player that is not
        // gliding and the server sends no flags to say it.
        boolean login = packet instanceof ClientboundLoginPacket;
        if (login || packet instanceof ClientboundRespawnPacket respawn
            && !respawn.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA)) {
            serverGlides = false;
            fresh = true;
            if (login) {
                joined = true;
            }
            return;
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || !(packet instanceof ClientboundSetEntityDataPacket data)
            || data.id() != player.getId()) {
            return;
        }
        for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
            if (value.id() == SHARED_FLAGS && value.value() instanceof Byte flags) {
                boolean glides = (flags & GLIDE_FLAG) != 0;
                if (glides != serverGlides) {
                    turns.incrementAndGet();
                }
                serverGlides = glides;
            }
        }
    }

    // Last of all because a packet another handler cancels never went out. Every glide
    // command turns the server's glide over whoever sent it. The game sends its own on a
    // jump in the air.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundPlayerCommandPacket command
            && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
            turnOver();
        }
    }

    // First of all because the flag has to be right before any module reads it. The server
    // sends the flags for other reasons too and such an echo would flip the flag back for a
    // tick. Only the glide it shows turning over as often as commands went out is the answer.
    @Subscribe(priority = Subscribe.FIRST)
    private void onTick(TickEvent event) {
        ticks++;
        startAfresh();
        LocalPlayer player = OfflineClient.MC.player;
        if (wait == Wait.NONE || player == null) {
            return;
        }
        // One reading for the whole tick. The network thread adds to the turns meanwhile.
        int seen = sent.seen();
        learn(sent.firstAnswer(seen));
        boolean answered = sent.answeredBy(seen);
        // A glide that touches down is shut by the server itself. That needs no wait.
        boolean landed = expected && player.onGround();
        if (wait == Wait.LATE) {
            endLate(answered);
        } else if (answered || landed || sent.waited() >= patience()) {
            endWait(player, answered, landed);
        } else if (wait == Wait.HOLDING) {
            set(player, wish);
        }
    }

    // A join or a respawn leaves nothing owed and the pause after a refusal runs down.
    private void startAfresh() {
        if (fresh) {
            fresh = false;
            wait = Wait.NONE;
            refused = 0;
        }
        if (joined) {
            joined = false;
            slowest = FIRST_GUESS;
        }
        if (refused > 0) {
            refused--;
        }
    }

    // The first turn of a wait shows how slow the server is. A quicker one brings the
    // figure down a tick at a time.
    private void learn(int took) {
        if (took > 0) {
            slowest = Math.clamp(Math.max(took, slowest - 1), FIRST_GUESS, SLOWEST);
        }
    }

    // The wait ran out earlier. An answer that still comes means the open was not refused
    // after all.
    private void endLate(boolean answered) {
        if (answered) {
            refused = 0;
        }
        if (answered || sent.waited() > CLOSE_TICKS) {
            wait = Wait.NONE;
        }
    }

    // The wait is over. Unanswered it runs on as a late one and an open the server never
    // showed is taken for refused.
    private void endWait(LocalPlayer player, boolean answered, boolean landed) {
        boolean held = wait == Wait.HOLDING;
        wait = answered ? Wait.NONE : Wait.LATE;
        if (!answered && opening() && !serverGlides && !landed) {
            refused = REFUSED_TICKS;
        }
        if (held) {
            follow(player, answered);
        }
    }

    // The wish that waited goes out once the server has answered and still holds the other
    // state. An open is no use to a player who has landed since. The flag goes first as it
    // does for every command. With nothing to send it goes to what the server shows.
    private void follow(LocalPlayer player, boolean answered) {
        boolean again = answered && serverGlides != wish && !(wish && player.onGround());
        set(player, again ? wish : serverGlides);
        if (again) {
            ask(wish);
        }
    }

    // True whilst one open and nothing else is on its way. Only that can be refused.
    private boolean opening() {
        return expected && sent.alone();
    }

    // How long the answer may take. The ping the tab list gives reads low for some time after
    // a join and is only a floor under what earlier answers took.
    private int patience() {
        return opening() ? Math.max(ServerInfo.answerTicks(), 2 * slowest + SLACK_TICKS) : CLOSE_TICKS;
    }
}
