package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Walls the player in with obsidian so crystals cannot reach their feet.
 * Missing support under a side is filled first.
 */
public final class Surround extends Module {

    private final BoolSetting center = new BoolSetting("Center",
        "Snap to the middle of your block first so every side lines up.", true);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Wait until you are standing on something.", true);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one tick.", 4, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 0, 0, 5, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block as it goes down.", true);
    private final BoolSetting toggleOnDone = new BoolSetting("Toggle off when done",
        "Turn off once all four sides are filled.", false);
    private final BoolSetting toggleOnMove = new BoolSetting("Toggle off on move",
        "Turn off if you leave the block you started on. Otherwise the wall follows you.", false);
    private final BoolSetting render = new BoolSetting("Show sides",
        "Outline the four side positions. Green when safe and red when open.", true);

    private int timer;
    private BlockPos anchor;
    private int previousSlot = -1;

    public Surround() {
        super("Surround", "Places obsidian around your feet to block crystals.", Category.COMBAT);
        addSettings(center, onlyOnGround, perTick, delay, rotate, toggleOnDone, toggleOnMove, render);
        searchTags("obsidian", "crystal", "hole");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        anchor = null;
        previousSlot = -1;
        if (inGame()) {
            anchor = mc.player.blockPosition();
            if (center.isOn()) {
                centerPlayer();
            }
        }
    }

    @Override
    protected void onDisable() {
        restoreSlot();
        anchor = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // Handles a first tick after enabling from the GUI before a world was loaded.
        if (anchor == null) {
            anchor = mc.player.blockPosition();
        }
        BlockPos feet = mc.player.blockPosition();
        if (!feet.equals(anchor)) {
            if (toggleOnMove.isOn()) {
                setEnabled(false);
                return;
            }
            // The wall follows the player's current block.
            anchor = feet;
        }
        if (onlyOnGround.isOn() && !mc.player.onGround()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        List<BlockPos> missing = missingSides(feet);
        // Off the ground the floor of the pocket comes first.
        if (!mc.player.onGround() && canFill(feet.below())) {
            missing.add(0, feet.below());
        }
        if (missing.isEmpty()) {
            restoreSlot();
            if (toggleOnDone.isOn()) {
                setEnabled(false);
            }
            return;
        }

        int slot = findBlastBlock();
        if (slot == -1) {
            restoreSlot();
            return;
        }
        if (center.isOn()) {
            centerPlayer();
        }

        int placed = 0;
        for (BlockPos pos : missing) {
            if (placed >= perTick.getInt()) {
                break;
            }
            BlockPos target = pos;
            Direction support = BlockUtil.findSupport(pos);
            if (support == null) {
                // Nothing to build against. Fill under the side first.
                BlockPos below = pos.below();
                if (canFill(below) && BlockUtil.findSupport(below) != null) {
                    target = below;
                    support = BlockUtil.findSupport(below);
                } else {
                    // With no support at all the empty spot is clicked directly like AirPlace does.
                    selectSlot(slot);
                    if (BlockUtil.placeDirect(pos, rotate.isOn(), true)) {
                        placed++;
                    }
                    continue;
                }
            }
            selectSlot(slot);
            if (BlockUtil.place(target, support, rotate.isOn(), true)) {
                placed++;
            }
        }

        if (placed > 0) {
            timer = delay.getInt();
        }
        // Give the hand back straight away.
        restoreSlot();
    }

    /** The side positions that still need a block. */
    private List<BlockPos> missingSides(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            if (canFill(pos)) {
                result.add(pos);
            }
        }
        return result;
    }

    /** True if the spot is open and no entity is standing in it. */
    private boolean canFill(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos)) {
            return false;
        }
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        return mc.level.isUnobstructed(obsidian, pos, CollisionContext.empty());
    }

    /** Hotbar slot with a blast proof block or minus one. */
    private int findBlastBlock() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem item
                && item.getBlock().getExplosionResistance() >= 600
                && item.getBlock().defaultDestroyTime() >= 0) {
                return i;
            }
        }
        return -1;
    }

    private void selectSlot(int slot) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (selected == slot) {
            return;
        }
        if (previousSlot == -1) {
            previousSlot = selected;
        }
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    /** Moves to the middle of the current block and tells the server. */
    private void centerPlayer() {
        double x = Mth.floor(mc.player.getX()) + 0.5;
        double z = Mth.floor(mc.player.getZ()) + 0.5;
        if (Math.abs(mc.player.getX() - x) < 0.01 && Math.abs(mc.player.getZ() - z) < 0.01) {
            return;
        }
        mc.player.setPos(x, mc.player.getY(), z);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, mc.player.getY(), z, mc.player.onGround(), mc.player.horizontalCollision));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            boolean safe = !BlockUtil.isReplaceable(pos);
            int color = safe ? 0xFF30E030 : 0xFFE03030;
            event.getBatch().outlineBox(new AABB(pos), color, false);
        }
    }
}
