package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;

// The server breaks blocks for a player through two slots and every break packet the client
// sends moves them. The target is the block the last slow start named. A stop only counts on
// the target and breaks it at once when the ticks since the last start of any block times its
// progress a tick reach the server's threshold. An early stop parks the target in the one
// delayed slot. The server finishes a parked block on its next tick once the time since you
// joined or respawned is longer than the block takes to break. Paper counts from its own start
// and nearly always finishes it. An early stop whilst that slot is taken does nothing. Packet
// miners ask here before they send and nothing they send goes to waste.
public final class BreakSlots {

    public static final BreakSlots INSTANCE = new BreakSlots();

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks past the full break time before a parked block counts as settled. Covers the
    // round trip to the server.
    private static final int PARK_GRACE_TICKS = 10;

    // The game clears a block it predicts broken before the server has answered. The slot
    // is only taken as free after this long. The server answers well within it.
    private static final int PREDICTED_PARK_TICKS = 3;

    private BlockPos target;
    private int startTick;

    // The block the game is about to break as it stood before a prediction cleared it.
    private BlockPos aimed;
    private BlockState aimedState;

    private BlockPos parked;
    private BlockState parkedState;
    private int parkedAt;
    private int parkedUntil;
    private boolean parkedPredicted;

    private ClientLevel level;
    private int lastTick;

    private BreakSlots() {
    }

    // True whilst nothing waits in the delayed slot. A slow block's pair only lands then.
    public static boolean parkingFree() {
        return INSTANCE.parked == null;
    }

    // True when the server would take a stop on its own for this block.
    public static boolean isTarget(BlockPos pos) {
        return pos.equals(INSTANCE.target);
    }

    // Ticks since the last start of any block. The server counts every stop from there.
    public static int sinceStart() {
        return clock() - INSTANCE.startTick;
    }

    // True when a stop sent now breaks a block with this much progress a tick outright.
    public static boolean stopFinishes(float perTick) {
        return perTick * (sinceStart() + 1) >= BlockUtil.SERVER_ACCEPTS;
    }

    // True whilst the game is part way through breaking a different block by hand or for a
    // module. A slow start elsewhere moves the target and that break would never land.
    public static boolean busyElsewhere(BlockPos pos) {
        MultiPlayerGameMode mode = MC.gameMode;
        return mode != null && mode.isDestroying()
            && !pos.equals(((MultiPlayerGameModeAccessor) mode).offlineclient$destroyBlockPos());
    }

    // Fires before the game predicts anything about the block.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (MC.level != null) {
            aimed = event.getPos().immutable();
            aimedState = MC.level.getBlockState(aimed);
        }
    }

    // Last of all because a packet another handler cancels never went out.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)
            || MC.player == null || MC.level == null) {
            return;
        }
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> started(packet);
            case STOP_DESTROY_BLOCK -> stopped(packet);
            case ABORT_DESTROY_BLOCK -> aborted(packet.getPos());
            default -> {
            }
        }
    }

    // A block that breaks in one hit goes on the start and leaves the target where it was.
    // A start on an empty spot still makes it the target.
    private void started(ServerboundPlayerActionPacket packet) {
        BlockPos pos = packet.getPos();
        BlockState state = serverState(packet);
        startTick = clock();
        if (!state.isAir() && breaksAtOnce(pos, state)) {
            return;
        }
        target = pos.immutable();
    }

    private void stopped(ServerboundPlayerActionPacket packet) {
        BlockPos pos = packet.getPos();
        BlockState state = serverState(packet);
        if (!pos.equals(target) || state.isAir()) {
            return;
        }
        float perTick = state.getDestroyProgress(MC.player, MC.level, pos);
        if (!stopFinishes(perTick) && parked == null) {
            park(pos, state, perTick, predicted(packet));
        }
    }

    // The block as the server still holds it. The game clears a block it predicts broken
    // before its own packet goes out.
    private BlockState serverState(ServerboundPlayerActionPacket packet) {
        BlockState state = MC.level.getBlockState(packet.getPos());
        if (state.isAir() && predicted(packet) && packet.getPos().equals(aimed)) {
            return aimedState;
        }
        return state;
    }

    private static boolean breaksAtOnce(BlockPos pos, BlockState state) {
        return MC.player.getAbilities().instabuild || state.getDestroyProgress(MC.player, MC.level, pos) >= 1;
    }

    // Paper lets go of its target when an abort names a different block. Vanilla keeps it but
    // forgetting it only costs a fresh start where a lone stop would have done.
    private void aborted(BlockPos pos) {
        if (!pos.equals(target)) {
            target = null;
        }
    }

    private void park(BlockPos pos, BlockState state, float perTick, boolean predicted) {
        parked = pos.immutable();
        parkedState = state;
        parkedAt = clock();
        parkedPredicted = predicted;
        parkedUntil = perTick > 0
            ? startTick + (int) Math.ceil(1 / perTick) + PARK_GRACE_TICKS : Integer.MAX_VALUE;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        int now = clock();
        if (MC.level != level || now < lastTick) {
            forget();
            level = MC.level;
        }
        lastTick = now;
        if (parked != null && (now >= parkedUntil || settled(now))) {
            parked = null;
        }
    }

    // A parked block that has gone from the world is broken. A powered part that only flips
    // its state is still there. A predicted one reads as gone at once and waits for the
    // server's answer first.
    private boolean settled(int now) {
        if (parkedPredicted && now - parkedAt < PREDICTED_PARK_TICKS) {
            return false;
        }
        return !MC.level.getBlockState(parked).is(parkedState.getBlock());
    }

    private void forget() {
        target = null;
        aimed = null;
        parked = null;
        startTick = clock();
    }

    // The game numbers its own predicted breaks from one. Raw packets carry nought.
    private static boolean predicted(ServerboundPlayerActionPacket packet) {
        return packet.getSequence() > 0;
    }

    private static int clock() {
        return MC.player == null ? 0 : MC.player.tickCount;
    }
}
