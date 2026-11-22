package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

// Breaks a block put back on a spot the server still holds as its mining target. The server
// counts a stop from the last start of any block and takes it for whatever stands on the
// target by then. Once one block has been mined on a spot every later one set down there goes
// with a stop on its own for as long as nothing else is started. See BreakSlots.
public final class Rebreak {

    private static final Minecraft MC = OfflineClient.MC;

    private BlockPos struck;
    private int struckAt;
    private int answerBy;

    public static boolean stopBreaks(BlockPos pos) {
        return BreakSlots.standsOnTarget(pos) && BreakSlots.stopFinishes(progress(pos));
    }

    // Fires at a spot whether anything stands there or not and catches whatever lands. A stop
    // goes out whilst the spot is the server's target. A start makes it the target otherwise
    // unless a block is being mined somewhere else.
    public static void poke(BlockPos pos) {
        if (BreakSlots.isTarget(pos)) {
            BlockMiner.sendStop(pos);
        } else if (!BreakSlots.busyElsewhere(pos)) {
            BlockMiner.sendStart(pos);
        }
    }

    // Sends the stop once the server's count has run past the whole break time of the block. True
    // from the stop until the server has had time to answer and the caller mines nothing meanwhile
    // because a start would set the server's count back. False when the block has to be mined the
    // usual way.
    public boolean strike(BlockPos pos) {
        int now = MC.player.tickCount;
        if (pos.equals(struck)) {
            // The player clock starts again on a respawn and the wait is over then as well.
            if (now >= struckAt && now < answerBy) {
                return true;
            }
            // The server kept the block standing. It is not asked the same way twice.
            struck = null;
            return false;
        }
        if (!BreakSlots.standsOnTarget(pos) || progress(pos) * BreakSlots.sinceStart() < 1) {
            return false;
        }
        BlockMiner.sendStop(pos);
        struck = pos.immutable();
        struckAt = now;
        answerBy = now + ServerInfo.answerTicks();
        return true;
    }

    // The spot was seen empty. The next block set down there gets a stop of its own.
    public void emptied() {
        struck = null;
    }

    private static float progress(BlockPos pos) {
        return BlockUtil.state(pos).getDestroyProgress(MC.player, MC.level, pos);
    }
}
