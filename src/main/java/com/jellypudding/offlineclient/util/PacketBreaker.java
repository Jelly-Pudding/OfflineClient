package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// Break packets for many blocks a tick that keep to the server's two break slots. A block a
// single tick finishes goes out whenever it is due. A slower block is parked in the delayed
// slot and only one waits there at a time. Nothing that would move the server's target goes
// out whilst the game is breaking another block. See BreakSlots.
public final class PacketBreaker {

    // Ticks before a block that should have gone at once is sent for again.
    private static final int RETRY_TICKS = 10;

    // Ticks a slower block gets on top of its break time before it is sent for again.
    private static final int SLOW_GRACE_TICKS = 20;

    private final Cooldowns<BlockPos> sent = new Cooldowns<>();
    private BlockPos slow;
    private int slowUntil;

    // Once a tick. True after a respawn when everything was forgotten.
    public boolean tick() {
        return tick(pos -> true);
    }

    // A slower block the test turns down is given up on as well.
    public boolean tick(Predicate<BlockPos> stillWanted) {
        boolean restarted = sent.tick();
        // A block that went may be put back and is fair game again at once.
        sent.releaseIf(pos -> BlockUtil.state(pos).isAir());
        if (slow != null && (restarted || clock() >= slowUntil || !stillWanted.test(slow)
            || BlockUtil.state(slow).isAir())) {
            slow = null;
        }
        finishSlow();
        return restarted;
    }

    // A stop on its own breaks the slow block as soon as the server's count allows. It also
    // parks the block if its first stop found the delayed slot taken.
    private void finishSlow() {
        if (slow != null && BreakSlots.isTarget(slow) && BreakSlots.stopFinishes(progress(slow))) {
            BlockMiner.sendStop(slow);
        }
    }

    // Blocks a tick finishes are always due. A slower one waits for the delayed slot.
    public boolean canSend(BlockPos pos) {
        if (BlockUtil.breaksInOneTick(pos)) {
            return canSendInstant(pos);
        }
        return !sent.contains(pos) && slow == null && BreakSlots.parkingFree()
            && !BreakSlots.busyElsewhere(pos);
    }

    // Only blocks a single tick finishes. One hit blocks never move the server's target.
    public boolean canSendInstant(BlockPos pos) {
        if (sent.contains(pos) || !BlockUtil.breaksInOneTick(pos)) {
            return false;
        }
        return BlockUtil.canInstantBreak(pos) || !BreakSlots.busyElsewhere(pos);
    }

    public void send(BlockPos pos) {
        boolean quick = BlockUtil.breaksInOneTick(pos);
        int ticks = quick ? RETRY_TICKS : BlockUtil.breakTicks(pos) + SLOW_GRACE_TICKS;
        BlockMiner.breakInstantly(pos);
        sent.put(pos.immutable(), ticks);
        if (!quick) {
            slow = pos.immutable();
            slowUntil = clock() + ticks;
        }
    }

    // The slower block being broken or null.
    public BlockPos slowBlock() {
        return slow;
    }

    public void reset() {
        sent.clear();
        slow = null;
    }

    private static float progress(BlockPos pos) {
        return BlockUtil.state(pos).getDestroyProgress(OfflineClient.MC.player, OfflineClient.MC.level, pos);
    }

    private static int clock() {
        return OfflineClient.MC.player.tickCount;
    }
}
