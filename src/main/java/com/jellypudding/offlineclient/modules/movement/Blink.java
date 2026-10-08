package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.MoveGate;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

public final class Blink extends Module {

    private static final float COPY_HEALTH = 20;

    // A hit lets go before the modules that act on it see the attack.
    private static final int LET_GO_FIRST = 100;

    // An enemy has to back off this much past the range before the hold lets go. One on the
    // edge would start and end a blink every tick.
    private static final double LET_GO_SLACK = 1;

    private final BoolSetting showCopy = new BoolSetting("Show copy",
        "Leaves a copy of you standing where the blink began.", true);
    private final NumberSetting pulseDelay = new NumberSetting("Pulse delay",
        "Ticks between sends of the held movement. Nought holds it until Blink is off.",
        0, 0, 60, 1, " ticks").min(0);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Restarts after holding this many packets. Nought holds them for as long as you like.",
        200, 0, 1000, 10, " packets").min(0);
    private final BoolSetting nearEnemies = new BoolSetting("Only near enemies",
        "Holds your movement only whilst an enemy player is close.", false);
    private final NumberSetting enemyRange = new NumberSetting("Enemy range",
        "How close an enemy has to be before your movement is held.", 6, 2, 16, 0.5, " blocks")
        .min(0.5).under(nearEnemies);
    private final BoolSetting letGoOnAttack = new BoolSetting("Let go on attack",
        "Sends where you really are the moment you attack.", false);
    private final BoolSetting endOnTeleport = new BoolSetting("End on teleport",
        "Switches Blink off when the server moves you such as after a home command.", true);
    private final KeybindSetting cancelKey = new KeybindSetting("Cancel key",
        "Drops every held packet and puts you back where the server last saw you.",
        KeybindSetting.UNBOUND);

    // Filled from the packet thread and read by the HUD.
    private final Queue<Packet<?>> held = new LinkedBlockingQueue<>();

    // Packets from blinks already let go. They leave one position a tick and keep
    // going whilst the next blink holds its own.
    private final Queue<Packet<?>> sending = new ArrayDeque<>();

    // The last packet queued. A repeat of it is not worth holding.
    private volatile ServerboundMovePlayerPacket lastHeld;

    // The player the held packets were captured from. Weak to avoid pinning a dead world.
    private volatile WeakReference<LocalPlayer> owner = new WeakReference<>(null);

    // Packets sent by release come straight back through this handler.
    private boolean releasing;

    // False whilst no enemy is close and nothing is held.
    private volatile boolean holding;

    // Read by Blink whilst it is on and by the walk home once it is off.
    private final Lagback.Watcher moved = new Lagback.Watcher();

    private FakePlayer.Body copy;
    private Vec3 start;
    // Where the server holds you whilst the walk home is under way.
    private Vec3 walkedTo;
    private int timer;

    public Blink() {
        super("Blink", "Pauses your position updates until you turn it off.",
            Category.MOVEMENT);
        addSettings(showCopy, pulseDelay, limit, nearEnemies, enemyRange, letGoOnAttack, endOnTeleport, cancelKey);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        if (!holding) {
            return null;
        }
        return held.size() + " held "
            + String.format(Locale.ROOT, "%.1fs", timer / (float) SharedConstants.TICKS_PER_SECOND);
    }

    @Override
    protected void onEnable() {
        moved.sync();
        holding = false;
        if (!nearEnemies.isOn() || enemyClose()) {
            begin();
        }
    }

    @Override
    protected void onDisable() {
        release();
    }

    private void begin() {
        held.clear();
        lastHeld = null;
        timer = 0;
        owner = new WeakReference<>(mc.player);
        holding = true;
        if (!inGame()) {
            return;
        }
        start = mc.player.position();
        if (showCopy.isOn()) {
            copy = new FakePlayer.Body(mc.level, mc.player, COPY_HEALTH, true, true);
            mc.level.addEntity(copy);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (moved.happened()) {
            serverMoved();
            if (!isEnabled()) {
                return;
            }
        }
        boolean wanted = !nearEnemies.isOn() || enemyClose();
        if (wanted && !holding) {
            begin();
        } else if (!wanted && holding) {
            letGo();
        }
        if (!holding) {
            return;
        }
        timer++;
        int delay = pulseDelay.getInt();
        if (delay > 0 && timer >= delay) {
            release();
            begin();
        }
    }

    // The copy of you that Blink leaves carries your name and would keep the hold going.
    private boolean enemyClose() {
        double reach = enemyRange.getValue() + (holding ? LET_GO_SLACK : 0);
        return inGame() && EntityUtil.nearest(reach,
            entity -> EntityUtil.isOtherPlayer(entity) && EntityUtil.isEnemy(entity)) != null;
    }

    // The enemy left. One packet takes the server to where you are when the way is clear.
    // Otherwise the walk home sets off at once ahead of your own movement packet.
    private void letGo() {
        if (sending.isEmpty() && jumpHome()) {
            held.clear();
        }
        release();
        walkHome();
    }

    // The server put you somewhere new. Every held move and the walk home would pull you back.
    // Whilst nothing is held there is no blink to end.
    private void serverMoved() {
        held.clear();
        sending.clear();
        lastHeld = null;
        if (!holding) {
            return;
        }
        if (endOnTeleport.isOn()) {
            setEnabled(false);
            return;
        }
        removeCopy();
        begin();
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (!event.presses(cancelKey) || mc.gui.screen() != null) {
            return;
        }
        cancel();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = mc.player;
        if (releasing || !holding || player == null
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (player != owner.get()) {
            // A respawn or a new world means the held positions are worthless.
            held.clear();
            lastHeld = null;
            owner = new WeakReference<>(player);
        }
        event.cancel();
        if (!sameAs(lastHeld, packet)) {
            held.add(packet);
            lastHeld = packet;
        }
        if (limit.getInt() > 0 && held.size() >= limit.getInt()) {
            release();
            begin();
        }
    }

    // The server has to hold you where you are before the hit lands or it lands out of reach.
    // One packet takes you there when the way is clear. Otherwise the hit waits for the walk.
    @Subscribe(priority = LET_GO_FIRST)
    private void onAttack(PacketSendEvent event) {
        if (releasing || !holding || !letGoOnAttack.isOn() || !inGame()
            || !(event.getPacket() instanceof ServerboundAttackPacket attack)) {
            return;
        }
        if (sending.isEmpty() && jumpHome()) {
            held.clear();
        } else {
            event.cancel();
            queueHeld();
            sending.add(attack);
            held.clear();
            watch(drain);
        }
        removeCopy();
        begin();
    }

    // The last held packet that passes the test. Null whilst none does.
    private ServerboundMovePlayerPacket newestHeld(Predicate<ServerboundMovePlayerPacket> test) {
        ServerboundMovePlayerPacket newest = null;
        for (Packet<?> packet : held) {
            if (packet instanceof ServerboundMovePlayerPacket move && test.test(move)) {
                newest = move;
            }
        }
        return newest;
    }

    // Straight from where the server holds you to the newest spot in one position. The packet
    // carries the newest held look. Without it a hit sent next would land facing where the blink
    // began. A drop that hurts and a way with a wall on it go the long way.
    private boolean jumpHome() {
        ServerboundMovePlayerPacket look = newestHeld(ServerboundMovePlayerPacket::hasRotation);
        ServerboundMovePlayerPacket newest = newestHeld(ServerboundMovePlayerPacket::hasPosition);
        float yRot = look == null ? mc.player.getYRot() : look.getYRot(0);
        float xRot = look == null ? mc.player.getXRot() : look.getXRot(0);
        releasing = true;
        try {
            if (newest == null) {
                // Only turns were held and the newest of them is all the server needs.
                if (look != null) {
                    mc.player.connection.send(look);
                }
                return true;
            }
            Vec3 to = new Vec3(newest.getX(0), newest.getY(0), newest.getZ(0));
            if (start == null || mc.player.isPassenger() || start.y - to.y > DamageUtil.SAFE_FALL
                || !MoveGate.free() || !Hop.clearWay(mc.player, start, to)) {
                return false;
            }
            int fillers = MoveGate.fillersFor(start.distanceTo(to));
            if (fillers < 0) {
                return false;
            }
            MoveGate.fillers(fillers, false);
            return MoveGate.send(to, yRot, xRot, newest.isOnGround());
        } finally {
            releasing = false;
        }
    }

    // Standing still makes a stream of identical packets. One of them says it all.
    private static boolean sameAs(ServerboundMovePlayerPacket previous,
                                  ServerboundMovePlayerPacket next) {
        return previous != null
            && previous.isOnGround() == next.isOnGround()
            && previous.getYRot(-1) == next.getYRot(-1)
            && previous.getXRot(-1) == next.getXRot(-1)
            && previous.getX(-1) == next.getX(-1)
            && previous.getY(-1) == next.getY(-1)
            && previous.getZ(-1) == next.getZ(-1);
    }

    // Throws the held packets and the rest of the walk home away and puts the player back where
    // the server holds them.
    private void cancel() {
        Vec3 back = !sending.isEmpty() ? walkedTo : holding ? start : null;
        held.clear();
        sending.clear();
        lastHeld = null;
        if (inGame() && back != null) {
            mc.player.setPos(back);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        setEnabled(false);
    }

    // The walk home goes out a tick at a time and carries on after the module
    // is switched off.
    private void release() {
        LocalPlayer player = mc.player;
        if (player != null && player == owner.get()) {
            queueHeld();
        }
        held.clear();
        owner = new WeakReference<>(null);
        lastHeld = null;
        holding = false;
        start = null;
        removeCopy();
        timer = 0;
        if (!sending.isEmpty()) {
            watch(drain);
        }
    }

    // Adds the held packets to the walk home. Until the walk sends a position the server holds
    // you where this blink began.
    private void queueHeld() {
        if (sending.isEmpty()) {
            walkedTo = start;
        }
        sending.addAll(held);
    }

    // Runs after Blink's own tick has dropped what a teleport made stale.
    private final Object drain = new Object() {
        @Subscribe(priority = -1)
        private void onTick(TickEvent event) {
            if (walkHome()) {
                unwatch(this);
            }
        }
    };

    // Sends what waits up to and including the next position. True once nothing is left.
    private boolean walkHome() {
        LocalPlayer player = mc.player;
        if (player == null || !isEnabled() && moved.happened()) {
            sending.clear();
        }
        releasing = true;
        try {
            Packet<?> next;
            while ((next = sending.peek()) != null) {
                Vec3 to = next instanceof ServerboundMovePlayerPacket move && move.hasPosition()
                    ? new Vec3(move.getX(0), move.getY(0), move.getZ(0)) : null;
                if (to != null && !MoveGate.free()) {
                    return false;
                }
                sending.poll();
                player.connection.send(next);
                if (to != null) {
                    walkedTo = to;
                    return sending.isEmpty();
                }
            }
        } finally {
            releasing = false;
        }
        return true;
    }

    private void removeCopy() {
        if (copy != null && mc.level != null && copy.level() == mc.level) {
            mc.level.removeEntity(copy.getId(), Entity.RemovalReason.DISCARDED);
        }
        copy = null;
    }
}
