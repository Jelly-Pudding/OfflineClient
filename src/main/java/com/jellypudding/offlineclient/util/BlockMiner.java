package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

// Lets modules mine blocks the same way a held left click does.
// MinecraftMixin suppresses the vanilla abort that runs every tick the attack key is up.
public final class BlockMiner {

    private static final Minecraft MC = OfflineClient.MC;
    private static final int IDLE = Integer.MIN_VALUE / 2;

    private static int lastDriveTick = IDLE;
    private static int lastMineTick = IDLE;
    private static BlockPos target;
    private static int lastFirstTick = IDLE;
    private static BlockPos first;
    private static boolean selfCall;
    private static boolean ownTool;

    private BlockMiner() {
    }

    // Called every tick until the block is gone.
    // Switching to another block aborts the previous one just like a crosshair move does.
    public static boolean mine(BlockPos pos, boolean rotate) {
        return mine(pos, rotate, SwingMode.BOTH);
    }

    public static boolean mine(BlockPos pos, boolean rotate, SwingMode swing) {
        if (MC.player == null || MC.level == null || MC.gameMode == null || refused(pos)) {
            return false;
        }
        if (standsBy(pos)) {
            // The block in hand is let go of once. The one that goes first needs the server's
            // target to itself.
            release(pos);
            keepControl();
            return true;
        }
        Direction side = BlockUtil.facingSide(pos);
        if (rotate) {
            BlockUtil.faceVector(BlockUtil.hitPoint(pos, side), RotationPriority.MINE);
        }
        boolean accepted;
        selfCall = true;
        try {
            accepted = MC.gameMode.continueDestroyBlock(pos, side);
        } finally {
            selfCall = false;
        }
        if (!accepted) {
            return false;
        }
        swing.mine();
        keepControl();
        target = pos;
        lastMineTick = MC.player.tickCount;
        return true;
    }

    // For a module that picked its own tool. AutoTool leaves the hand alone whilst it mines.
    public static boolean mineWithHeld(BlockPos pos, boolean rotate) {
        return mineWithHeld(pos, rotate, SwingMode.BOTH);
    }

    public static boolean mineWithHeld(BlockPos pos, boolean rotate, SwingMode swing) {
        ownTool = true;
        try {
            return mine(pos, rotate, swing);
        } finally {
            ownTool = false;
        }
    }

    // Names a block that has to go before any other. Called every tick it is worked on by a
    // module that guards the player or opens an enemy up. Whoever else mines meanwhile is told
    // its block is still being mined and carries on once this one is done.
    public static void first(BlockPos pos) {
        first = pos.immutable();
        lastFirstTick = MC.player.tickCount;
    }

    // True whilst another block goes first.
    public static boolean standsBy(BlockPos pos) {
        return lately(lastFirstTick) && !pos.equals(first);
    }

    // Sends the start and stop packets for a block in one go.
    // The server parks anything slower than one tick and holds only one at a time.
    public static void breakInstantly(BlockPos pos) {
        sendStart(pos);
        sendStop(pos);
    }

    // A start on its own. It makes the block the server's target unless it breaks in one hit.
    public static void sendStart(BlockPos pos) {
        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos);
    }

    // A stop on its own. The server only takes it for its target and counts from the last start.
    public static void sendStop(BlockPos pos) {
        send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos);
    }

    // An abort on its own. Named on the server's target it wipes the cracks and lets go of the
    // block. It breaks nothing and NoInteract has no say over it.
    public static void sendAbort(BlockPos pos) {
        send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos);
    }

    private static void send(ServerboundPlayerActionPacket.Action action, BlockPos pos) {
        if (MC.player == null || MC.level == null
            || action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK && refused(pos)) {
            return;
        }
        MC.player.connection.send(new ServerboundPlayerActionPacket(action, pos, BlockUtil.facingSide(pos)));
    }

    // True whilst NoInteract keeps the block standing for the lava it holds back. A module can
    // leave such a block out before it tries. Nothing here breaks one.
    public static boolean kept(BlockPos pos) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        return noInteract != null && noInteract.holdsBackLava(pos);
    }

    // Every break a module makes passes here. The hand asks NoInteract on its own.
    private static boolean refused(BlockPos pos) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        return noInteract != null && noInteract.refusesBreak(pos);
    }

    // Keeps vanilla mining suppressed for another tick without touching a block.
    public static void keepControl() {
        if (MC.player != null) {
            lastDriveTick = MC.player.tickCount;
        }
    }

    // True when a module drove mining this tick or the one before.
    public static boolean isActive() {
        return lately(lastDriveTick);
    }

    // True whilst the player's own held click mines a block. A module that mines is not the hand.
    public static boolean handMines() {
        return MC.gameMode != null && MC.gameMode.isDestroying() && !isActive();
    }

    private static boolean lately(int tick) {
        if (MC.player == null) {
            return false;
        }
        int since = MC.player.tickCount - tick;
        return since >= 0 && since <= 1;
    }

    // True whilst the game is inside a mining call made from here.
    public static boolean isSelfCall() {
        return selfCall;
    }

    // True whilst a module that picked its own tool is mining.
    public static boolean isOwnTool() {
        return ownTool;
    }

    // The block a module mined this tick or the one before. Null whilst none did.
    public static BlockPos getTarget() {
        return lately(lastMineTick) ? target : null;
    }

    // True whilst a module mines a block other than this one. A module outside the mining group
    // waits its turn because two blocks mined at once restart each other every tick.
    public static boolean minesOther(BlockPos pos) {
        BlockPos mined = getTarget();
        return mined != null && !mined.equals(pos);
    }

    // Sends the abort the server expects.
    public static void release() {
        if (isActive() && MC.gameMode != null) {
            MC.gameMode.stopDestroyBlock();
        }
        lastDriveTick = IDLE;
        target = null;
    }

    // Lets go of this block only whilst it is the one being mined. Another module's block stays.
    public static void release(BlockPos pos) {
        if (pos.equals(getTarget())) {
            release();
        }
    }
}
