package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// Break packets for many blocks a tick that keep to the server's two break slots. A block a
// single tick finishes goes out whenever it is due. A slower block is parked in the delayed
// slot and only one waits there at a time. Nothing that would move the server's target goes
// out whilst the game is breaking another block. See BreakSlots.
public final class PacketBreaker {

    // Each break is a start and a stop. Every row that sets how many go a tick ends with this.
    public static final String PAPER_LIMIT = "Paper kicks a client that keeps up more than about twelve a tick.";

    // Ticks before a block that should have gone at once is sent for again.
    private static final int RETRY_TICKS = 10;

    // Ticks a slower block gets on top of its break time before it is sent for again.
    private static final int SLOW_GRACE_TICKS = 20;

    private final Cooldowns<BlockPos> sent = new Cooldowns<>();
    private BlockPos slow;
    private int slowUntil;

    // The Blocks per tick row of a module with an Instant mode.
    public static NumberSetting blocksPerTick() {
        return new NumberSetting("Blocks per tick",
            "The most blocks Instant mode breaks in one tick. " + PAPER_LIMIT, 4, 1, 16, 1);
    }

    // Once a tick. True after a respawn when everything was forgotten.
    public boolean tick() {
        return tick(pos -> true);
    }

    // A slower block the test turns down or NoInteract keeps standing is given up on as well.
    public boolean tick(Predicate<BlockPos> stillWanted) {
        boolean restarted = sent.tick();
        // A block that went may be put back and is fair game again at once.
        sent.releaseIf(pos -> BlockUtil.state(pos).isAir());
        if (slow != null && (restarted || clock() >= slowUntil || !stillWanted.test(slow)
            || BlockUtil.state(slow).isAir() || BlockMiner.kept(slow))) {
            slow = null;
        }
        finishSlow();
        return restarted;
    }

    // A stop on its own breaks the slow block as soon as the server's count allows. It also
    // parks the block if its first stop found the delayed slot taken.
    private void finishSlow() {
        if (slow != null && Rebreak.stopBreaks(slow)) {
            BlockMiner.sendStop(slow);
        }
    }

    // Blocks a tick finishes are always due. A slower one waits for the delayed slot. A block
    // NoInteract keeps standing is never due and never holds up the rest.
    public boolean canSend(BlockPos pos) {
        if (BreakTime.breaksInOneTick(pos)) {
            return canSendInstant(pos);
        }
        return !sent.contains(pos) && slow == null && BreakSlots.parkingFree()
            && !BreakSlots.busyElsewhere(pos) && !BlockMiner.kept(pos);
    }

    // Only blocks a single tick finishes. One hit blocks never move the server's target.
    public boolean canSendInstant(BlockPos pos) {
        if (sent.contains(pos) || !BreakTime.breaksInOneTick(pos) || BlockMiner.kept(pos)) {
            return false;
        }
        return BreakTime.canInstantBreak(pos) || !BreakSlots.busyElsewhere(pos);
    }

    public void send(BlockPos pos) {
        boolean quick = BreakTime.breaksInOneTick(pos);
        int ticks = quick ? RETRY_TICKS : BreakTime.breakTicks(pos) + SLOW_GRACE_TICKS;
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

    private static int clock() {
        return OfflineClient.MC.player.tickCount;
    }
}
