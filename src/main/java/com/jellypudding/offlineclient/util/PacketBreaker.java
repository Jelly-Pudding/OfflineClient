package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// Break packets for many blocks a tick. A one hit block goes straight out and is tried
// again after a pause if it is still there. A slower block goes one at a time and holds
// the rest back until its break time has run out.
public final class PacketBreaker {

    // Ticks before the same one hit block is sent again.
    private static final int RETRY_TICKS = 10;

    // Ticks a slower block gets on top of its break time before it is given up on.
    private static final int SLOW_GRACE_TICKS = 20;

    private final Cooldowns<BlockPos> sent = new Cooldowns<>();
    private BlockPos slow;
    private int slowUntil;

    // Once a tick. True after a respawn when everything was forgotten.
    public boolean tick() {
        return tick(pos -> true);
    }

    // A slower block the test no longer wants is given up on as well.
    public boolean tick(Predicate<BlockPos> stillWanted) {
        boolean restarted = sent.tick();
        if (slow != null && (restarted || clock() >= slowUntil || !stillWanted.test(slow))) {
            slow = null;
        }
        return restarted;
    }

    // One hit blocks only whilst a slower block is on its way.
    public boolean canSend(BlockPos pos) {
        return !sent.contains(pos) && (slow == null || BlockUtil.canInstantBreak(pos));
    }

    // Instant blocks alone never queue behind a slower one.
    public boolean canSendInstant(BlockPos pos) {
        return !sent.contains(pos) && BlockUtil.canInstantBreak(pos);
    }

    public void send(BlockPos pos) {
        boolean instant = BlockUtil.canInstantBreak(pos);
        BlockMiner.breakInstantly(pos);
        if (instant) {
            sent.put(pos, RETRY_TICKS);
            return;
        }
        int ticks = BlockUtil.breakTicks(pos) + SLOW_GRACE_TICKS;
        slow = pos;
        slowUntil = clock() + ticks;
        sent.put(pos, ticks);
    }

    // The slower block being broken or null.
    public BlockPos slowBlock() {
        return slow;
    }

    public void reset() {
        sent.clear();
        slow = null;
    }

    private static int clock() {
        return OfflineClient.MC.player.tickCount;
    }
}
