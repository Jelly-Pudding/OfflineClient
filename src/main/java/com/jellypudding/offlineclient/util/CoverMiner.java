package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// Mines one block of an enemy's cover for a combat module. The module names the block every tick
// and says when the packet that breaks it may go. Only one miner works at a time and the rest wait
// their turn. See BreakSlots for how the server counts a break.
public final class CoverMiner {

    public enum Mode { HOLD, PACKET, SILENT }

    private static final Minecraft MC = OfflineClient.MC;
    private static final int IDLE = Integer.MIN_VALUE / 2;

    private static final int SETTLE_TICKS = 5;

    // Stops in a row that left the block standing before the count is started afresh. The
    // server counts on something else by then.
    private static final int MAX_MISSES = 3;

    // The miner the server's break target belongs to whilst it works.
    private static CoverMiner owner;

    // The spot the last start of any miner went out for whilst the block it was sent for
    // still stands. The server counts a stop there from that start.
    private static BlockPos startedAt;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mine mode",
        "How the block is mined.", Mode.HOLD)
        .describe(Mode.HOLD, "Mines it like a held left click with the tool in hand throughout.")
        .describe(Mode.PACKET, "Sends the start and stop at once and keeps the tool out whilst the server counts.")
        .describe(Mode.SILENT, "Sends the start and stop at once and puts the tool away until the last packet.");
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds the tool AutoTool would pick or else your fastest one.", true);

    private final BooleanSupplier rotate;
    private final Supplier<SwingMode> swing;
    private final SlotSwap slots = new SlotSwap();
    private BlockPos current;
    private int stopSentAt = IDLE;
    private int misses;
    private int seenAt = IDLE;

    public CoverMiner(BooleanSupplier rotate, Supplier<SwingMode> swing) {
        this.rotate = rotate;
        this.swing = swing;
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {mode, autoTool};
    }

    public CoverMiner under(BoolSetting parent) {
        mode.under(parent);
        autoTool.under(parent);
        return this;
    }

    public BlockPos target() {
        return current;
    }

    public boolean keepsTool() {
        return owner == this && current != null && !mode.is(Mode.SILENT) && !BlockUtil.state(current).isAir();
    }

    public boolean waiting() {
        return (owner != null && owner != this && owner.working()) || Modules.packetMining();
    }

    private boolean working() {
        int since = MC.player.tickCount - seenAt;
        return current != null && since >= 0 && since <= 1;
    }

    // One tick of work on the block. Whilst mayFinish is false the packet that breaks it is
    // held back. The follow up runs straight after that packet with the tool still in hand.
    // True on the tick the block is broken or the packet meant to break it goes out.
    public boolean drive(BlockPos pos, boolean mayFinish, Runnable followUp) {
        if (!claim(pos)) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        if (state.isAir()) {
            cleared();
            return false;
        }
        if (!BlockUtil.serverReaches(pos)) {
            return false;
        }
        int tool = toolSlot(state);
        boolean byHand = MC.gameMode.isDestroying();
        boolean counted = BreakSlots.isTarget(pos) && !byHand;
        boolean ready = counted && BreakTime.breakDelta(toolFor(state), pos) * (BreakSlots.sinceStart() + 1) >= 1;
        if (ready && misses < MAX_MISSES) {
            return mayFinish && finish(pos, tool, followUp);
        }
        if (mode.is(Mode.HOLD)) {
            return mineByHand(pos, state, tool, mayFinish, followUp);
        }
        // A start would cut off whatever the player is breaking by hand.
        if (byHand) {
            return false;
        }
        // The server is left to count on the start sent for this block. A block put back after
        // its spot broke counts from an older start and gets one of its own. That parks it and
        // the plain server finishes a parked block on its next tick.
        if (counted && !ready && pos.equals(startedAt)) {
            // The server finishes a parked block by itself with the tool in hand.
            if (mode.is(Mode.PACKET) && tool != -1) {
                slots.select(tool);
            }
            return false;
        }
        // A block the first packet breaks waits for the go ahead as well.
        if (!mayFinish && BreakTime.breakDelta(toolFor(state), pos) >= 1) {
            return false;
        }
        misses = 0;
        return start(pos, tool, mayFinish, followUp);
    }

    public boolean settling() {
        int since = MC.player.tickCount - stopSentAt;
        return since >= 0 && since < SETTLE_TICKS;
    }

    public int ticksLeft(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        float perTick = BreakTime.breakDelta(toolFor(state), pos);
        if (state.isAir() || perTick <= 0) {
            return Integer.MAX_VALUE;
        }
        MultiPlayerGameModeAccessor game = (MultiPlayerGameModeAccessor) MC.gameMode;
        float done = 0;
        if (MC.gameMode.isDestroying() && pos.equals(game.offlineclient$destroyBlockPos())) {
            done = game.offlineclient$destroyProgress();
        } else if (BreakSlots.isTarget(pos)) {
            done = perTick * (BreakSlots.sinceStart() + 1);
        }
        return Math.max(0, (int) Math.ceil((1 - done) / perTick));
    }

    // Keeps the server counting on a spot it has broken with the hand given back. Whatever is
    // put back there then goes with a single stop.
    public void guard(BlockPos pos) {
        if (!claim(pos)) {
            return;
        }
        cleared();
        slots.restore();
        if (!BreakSlots.isTarget(pos)) {
            Rebreak.poke(pos);
        }
    }

    public void rest() {
        slots.standAside();
        if (current != null && MC.player != null) {
            seenAt = MC.player.tickCount;
        }
    }

    public void stop() {
        if (owner == this && current != null && MC.player != null && MC.level != null) {
            BlockMiner.release(current);
            if (!mode.is(Mode.HOLD) && BreakSlots.standsOnTarget(current)) {
                BlockMiner.sendAbort(current);
            }
        }
        slots.restore();
        current = null;
        stopSentAt = IDLE;
        misses = 0;
        if (owner == this) {
            owner = null;
        }
    }

    // The block reads gone. The last stop did its work and a block put back there is new.
    private void cleared() {
        stopSentAt = IDLE;
        misses = 0;
        if (current.equals(startedAt)) {
            startedAt = null;
        }
    }

    // Minus one keeps what is held.
    private int toolSlot(BlockState state) {
        return autoTool.isOn() ? Modules.toolSlot(state) : -1;
    }

    private ItemStack toolFor(BlockState state) {
        int slot = toolSlot(state);
        return slot == -1 ? MC.player.getMainHandItem() : MC.player.getInventory().getItem(slot);
    }

    // False whilst another miner has the server's break target.
    private boolean claim(BlockPos pos) {
        if (waiting()) {
            slots.restore();
            return false;
        }
        // A module that mines another block the slow way lets go of it on its next tick.
        BlockMiner.first(pos);
        if (BlockMiner.minesOther(pos)) {
            slots.restore();
            return false;
        }
        if (!pos.equals(current)) {
            stop();
            current = pos.immutable();
        }
        owner = this;
        seenAt = MC.player.tickCount;
        return true;
    }

    private boolean finish(BlockPos pos, int tool, Runnable followUp) {
        if (settling()) {
            return false;
        }
        withTool(pos, tool, () -> {
            BlockMiner.sendStop(pos);
            swing.get().mine();
            followUp.run();
        });
        stopSentAt = MC.player.tickCount;
        misses++;
        return true;
    }

    // The start goes out once with the tool in hand and the server counts from there. The
    // stop goes with it unless the break has to wait. A block one tick breaks goes with the pair.
    private boolean start(BlockPos pos, int tool, boolean parked, Runnable followUp) {
        startedAt = pos.immutable();
        boolean[] broke = new boolean[1];
        withTool(pos, tool, () -> {
            broke[0] = parked ? BreakTime.breaksInOneTick(pos) : BreakTime.canInstantBreak(pos);
            if (parked) {
                BlockMiner.breakInstantly(pos);
            } else {
                BlockMiner.sendStart(pos);
            }
            swing.get().mine();
            if (broke[0]) {
                followUp.run();
            }
        });
        return broke[0];
    }

    private boolean mineByHand(BlockPos pos, BlockState state, int tool, boolean mayFinish, Runnable followUp) {
        if (tool != -1) {
            slots.select(tool);
        }
        // The game breaks the block itself on the tick its own count fills up.
        if (!mayFinish && handFinishes(pos, state)) {
            BlockMiner.keepControl();
            return false;
        }
        if (!BlockMiner.mine(pos, rotate.getAsBoolean(), swing.get())) {
            return false;
        }
        if (!BlockUtil.state(pos).isAir()) {
            return false;
        }
        followUp.run();
        return true;
    }

    private static boolean handFinishes(BlockPos pos, BlockState state) {
        MultiPlayerGameModeAccessor game = (MultiPlayerGameModeAccessor) MC.gameMode;
        return MC.gameMode.isDestroying() && pos.equals(game.offlineclient$destroyBlockPos())
            && game.offlineclient$destroyProgress() + state.getDestroyProgress(MC.player, MC.level, pos) >= 1;
    }

    private void withTool(BlockPos pos, int tool, Runnable packets) {
        if (rotate.getAsBoolean()) {
            BlockUtil.faceVector(BlockUtil.hitPoint(pos, BlockUtil.facingSide(pos)), RotationPriority.MINE);
        }
        if (tool != -1) {
            slots.select(tool);
        }
        packets.run();
        if (mode.is(Mode.SILENT)) {
            slots.restore();
        }
    }
}
