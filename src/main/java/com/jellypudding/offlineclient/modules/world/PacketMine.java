package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Breaks one chosen block with a start and stop pair instead of a held click.
 * The server finishes the block on its own.
 */
public final class PacketMine extends Module {

    private static final int MINING_COLOR = 0xFFFF5030;
    private static final int READY_COLOR = 0xFF40FF60;

    // Ticks of grace before a block that will not break is given up on.
    private static final int PATIENCE_TICKS = 50;

    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds your fastest tool whilst the block breaks.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the block on the server side.", true);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws a box that turns green when the block is due to fall.", true);
    private final BoolSetting rebreak = new BoolSetting("Rebreak",
        "Starts again on whatever is put back in the same spot.", false);
    private final BoolSetting instantRebreak = new BoolSetting("Instant rebreak",
        "Fires at the spot every tick to catch a replacement the moment it lands.", true)
        .visibleWhen(rebreak::isOn);

    private BlockPos target;
    private Block mined;
    private int startTick;
    private boolean mining;

    private final SlotSwap slots = new SlotSwap();

    // Only a fresh press picks a target.
    private boolean attackHeld;

    public PacketMine() {
        super("PacketMine", "Keeps breaking one block you clicked whilst you do other things.", Category.WORLD);
        addSettings(autoTool, rotate, render, rebreak, instantRebreak);
        searchTags("obsidian", "packet mine", "instant mine");
    }

    @Override
    public String getSuffix() {
        if (target == null) {
            return "no target";
        }
        if (!mining) {
            return "waiting";
        }
        return Math.min(100, (int) (progress() * 100)) + "%";
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        clear();
        attackHeld = mc.options.keyAttack.isDown();
    }

    @Override
    protected void onDisable() {
        if (mining && inGame()) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target, Direction.DOWN));
        }
        BlockMiner.release();
        slots.restoreIfMine();
        clear();
    }

    private void clear() {
        target = null;
        mined = null;
        mining = false;
    }

    // Samples the attack key after the game has handled this tick's clicks.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        attackHeld = mc.options.keyAttack.isDown();
    }

    // A fresh left click on a block makes it the target.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (attackHeld || BlockMiner.isSelfCall() || !inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos pos = event.getPos();
        if (pos.equals(target) || !BlockUtil.isBreakable(pos)) {
            return;
        }
        slots.restoreIfMine();
        target = pos.immutable();
        mined = null;
        mining = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            clear();
            return;
        }
        if (target == null) {
            slots.restoreIfMine();
            return;
        }
        if (BlockUtil.distanceTo(target) > mc.player.blockInteractionRange()) {
            giveUp("§bPacketMine §7dropped its target because it went out of reach.");
            return;
        }

        BlockState state = BlockUtil.state(target);
        if (mining && (state.isAir() || state.getBlock() != mined)) {
            broken();
            return;
        }
        if (!mining) {
            if (state.isAir() || !BlockUtil.isBreakable(target)) {
                if (!rebreak.isOn()) {
                    clear();
                    slots.restoreIfMine();
                } else if (instantRebreak.isOn()) {
                    poke();
                }
                return;
            }
            start(state);
            return;
        }
        if (progress() > 2 && mc.player.tickCount - startTick > PATIENCE_TICKS) {
            giveUp("§bPacketMine §7gave up on that block.");
            return;
        }
        holdTool(state);
    }

    private void start(BlockState state) {
        // The pair goes out once the server has an angle on the block.
        if (rotate.isOn() && !RotationManager.look(BlockUtil.hitPoint(target, BlockUtil.facingSide(target)),
            RotationPriority.MINE, RotationManager.BLOCK_TOLERANCE)) {
            return;
        }
        holdTool(state);
        BlockMiner.breakInstantly(target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        mined = state.getBlock();
        startTick = mc.player.tickCount;
        mining = true;
    }

    // Rebreak waits for the next block in that spot.
    private void broken() {
        mining = false;
        mined = null;
        if (!rebreak.isOn()) {
            target = null;
            slots.restoreIfMine();
        }
    }

    // Breaks the replacement on the tick it lands. Ignored whilst the spot is empty.
    private void poke() {
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, Direction.UP));
    }

    private void giveUp(String message) {
        ChatUtil.message(message);
        clear();
        slots.restoreIfMine();
    }

    // A client side estimate of how far along the server is.
    private double progress() {
        if (!mining || target == null || !inGame()) {
            return 0;
        }
        BlockState state = BlockUtil.state(target);
        if (state.isAir()) {
            return 1;
        }
        return state.getDestroyProgress(mc.player, mc.level, target) * (mc.player.tickCount - startTick + 1);
    }

    private void holdTool(BlockState state) {
        if (!autoTool.isOn() || mc.player.isUsingItem()) {
            return;
        }
        // A slot the player picked themselves is left alone.
        if (slots.isHolding() && !slots.stillMine()) {
            slots.forget();
            return;
        }
        ItemUtil.selectBestTool(state, slots);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || target == null || !inGame()) {
            return;
        }
        double done = Math.clamp(progress(), 0, 1);
        int color = ColorUtil.lerp(MINING_COLOR, READY_COLOR, (float) done);
        AABB box = DrawBatch.blockBox(target);
        event.getBatch().outlineBox(box, color, true);
        if (done > 0) {
            AABB filled = new AABB(box.minX, box.minY, box.minZ,
                box.maxX, box.minY + (box.maxY - box.minY) * done, box.maxZ);
            event.getBatch().solidBox(filled, ColorUtil.withAlpha(color, 60), true);
        }
    }
}
