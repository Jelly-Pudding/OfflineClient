package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

/**
 * Lets modules mine blocks the same way a held left click does.
 * MinecraftMixin suppresses the vanilla abort that normally happens
 * every tick the attack key is up.
 */
public final class BlockMiner {

    private static final Minecraft MC = OfflineClient.MC;
    private static final int IDLE = Integer.MIN_VALUE / 2;

    private static int lastDriveTick = IDLE;
    private static BlockPos target;
    private static boolean selfCall;

    private BlockMiner() {
    }

    /**
     * Called every tick until the block is gone. Switching to another block
     * aborts the old one just like a crosshair move does.
     */
    public static boolean mine(BlockPos pos, boolean rotate) {
        if (MC.player == null || MC.level == null || MC.gameMode == null) {
            return false;
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
        MC.player.swing(InteractionHand.MAIN_HAND);
        keepControl();
        target = pos;
        return true;
    }

    /**
     * Sends the start and stop packets for a block in one go. The server
     * queues anything slower than one hit and holds only one at a time.
     */
    public static void breakInstantly(BlockPos pos) {
        if (MC.player == null || MC.level == null) {
            return;
        }
        Direction side = BlockUtil.facingSide(pos);
        MC.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side));
        MC.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, side));
    }

    // Keeps vanilla mining suppressed for another tick without touching a block.
    public static void keepControl() {
        if (MC.player != null) {
            lastDriveTick = MC.player.tickCount;
        }
    }

    // True when a module drove mining this tick or the one before.
    public static boolean isActive() {
        if (MC.player == null) {
            return false;
        }
        int sinceDrive = MC.player.tickCount - lastDriveTick;
        return sinceDrive >= 0 && sinceDrive <= 1;
    }

    // True whilst the game is inside a mining call made from here.
    public static boolean isSelfCall() {
        return selfCall;
    }

    public static BlockPos getTarget() {
        return isActive() ? target : null;
    }

    // Sends the abort the server expects.
    public static void release() {
        if (isActive() && MC.gameMode != null) {
            MC.gameMode.stopDestroyBlock();
        }
        lastDriveTick = IDLE;
        target = null;
    }
}
