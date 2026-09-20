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
import com.jellypudding.offlineclient.util.MoveGate;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public final class Blink extends Module {

    private static final float COPY_HEALTH = 20;

    private final BoolSetting showCopy = new BoolSetting("Show copy",
        "Leaves a copy of you standing where the blink began.", true);
    private final NumberSetting pulseDelay = new NumberSetting("Pulse delay",
        "Sends the held packets and starts again after this many ticks. 0 never does.",
        0, 0, 60, 1, " ticks").min(0);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Restarts after holding this many packets. Nought holds them for as long as you like.",
        200, 0, 1000, 10, " packets").min(0);
    private final KeybindSetting cancelKey = new KeybindSetting("Cancel key",
        "Drops every held packet and puts you back where the blink began.",
        KeybindSetting.UNBOUND);

    // Filled from the packet thread and read by the HUD.
    private final Queue<Packet<?>> held = new LinkedBlockingQueue<>();

    // The last packet queued. A repeat of it is not worth holding.
    private volatile ServerboundMovePlayerPacket lastHeld;

    // The player the held packets were captured from. Weak to avoid pinning a dead world.
    private volatile WeakReference<LocalPlayer> owner = new WeakReference<>(null);

    // Packets sent by release come straight back through this handler.
    private boolean releasing;

    private FakePlayer.Body copy;
    private Vec3 start;
    private int timer;

    public Blink() {
        super("Blink", "Pauses your position updates until you turn it off.",
            Category.MOVEMENT);
        addSettings(showCopy, pulseDelay, limit, cancelKey);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return held.size() + " held " + String.format("%.1fs", timer / 20f);
    }

    @Override
    protected void onEnable() {
        // Drops a walk home that is still under way.
        unwatch(drain);
        begin();
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
        timer++;
        int delay = pulseDelay.getInt();
        if (delay > 0 && timer >= delay) {
            release();
            begin();
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || mc.gui.screen() != null
            || !cancelKey.isBound() || event.getKey() != cancelKey.getValue()) {
            return;
        }
        cancel();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = mc.player;
        if (releasing || player == null
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (player != owner.get()) {
            // A respawn or a new world means the held positions are worthless.
            held.clear();
            lastHeld = null;
            owner = new WeakReference<>(player);
        }
        if (limit.getInt() > 0 && held.size() >= limit.getInt()) {
            // Toggling the module from the packet thread would edit the bus mid dispatch.
            release();
            owner = new WeakReference<>(player);
            return;
        }
        event.cancel();
        if (sameAs(lastHeld, packet)) {
            return;
        }
        held.add(packet);
        lastHeld = packet;
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

    // Throws the held packets away and walks the player back to the start.
    private void cancel() {
        held.clear();
        lastHeld = null;
        if (inGame() && start != null) {
            mc.player.setPos(start);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        setEnabled(false);
    }

    // The walk home goes out a tick at a time and carries on after the module
    // is switched off.
    private void release() {
        LocalPlayer player = mc.player;
        LocalPlayer captured = owner.get();
        owner = new WeakReference<>(null);
        if (player == null || player != captured) {
            held.clear();
        }
        lastHeld = null;
        removeCopy();
        timer = 0;
        if (!held.isEmpty()) {
            watch(drain);
        }
    }

    private final Object drain = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            LocalPlayer player = mc.player;
            if (player == null) {
                held.clear();
            }
            releasing = true;
            try {
                Packet<?> next;
                while ((next = held.peek()) != null) {
                    boolean carriesPosition = next instanceof ServerboundMovePlayerPacket move
                        && move.hasPosition();
                    if (carriesPosition && !MoveGate.free()) {
                        return;
                    }
                    held.poll();
                    player.connection.send(next);
                    if (carriesPosition) {
                        return;
                    }
                }
            } finally {
                releasing = false;
            }
            unwatch(this);
        }
    };

    private void removeCopy() {
        if (copy != null && mc.level != null && copy.level() == mc.level) {
            mc.level.removeEntity(copy.getId(), Entity.RemovalReason.DISCARDED);
        }
        copy = null;
    }
}
