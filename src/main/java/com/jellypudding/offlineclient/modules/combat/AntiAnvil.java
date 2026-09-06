package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.phys.AABB;

// A dropped anvil is a falling entity until it lands.
// AntiAnvil places a block overhead while it is still falling.
public final class AntiAnvil extends Module {

    // How far above the head an anvil is worth catching. Higher ones take a while yet.
    private static final int WATCH_HEIGHT = 12;

    // Two blocks over the feet is the first block clear of the player's own body.
    private static final int ROOF_OFFSET = 2;

    // A placed anvil sits as a block for a moment before it starts to fall.
    // Anything lower than this would already be touching the roof.
    private static final int FIRST_PLACED = 3;

    private static final int ROOF_COLOR = 0xFFFF9040;

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards the block.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Show roof",
        "Outline the block being placed.", true);

    private final SlotSwap slots = new SlotSwap();
    private BlockPos roof;

    public AntiAnvil() {
        super("AntiAnvil", "Puts a block over your head when an anvil is dropped on you.", Category.COMBAT);
        addSettings(rotate, swing, render);
        searchTags("anvil");
    }

    @Override
    public String getSuffix() {
        return roof == null ? null : "covering";
    }

    @Override
    protected void onEnable() {
        slots.forget();
        roof = null;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        roof = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        roof = null;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        if (!anvilOverhead() && !anvilPlacedOverhead(feet)) {
            slots.restore();
            return;
        }
        BlockPos target = feet.above(ROOF_OFFSET);
        // Somebody has already placed the roof or the anvil is stuck on a block in between.
        if (!BlockUtil.state(target).isAir() && !BlockUtil.isReplaceable(target)) {
            return;
        }
        roof = target;
        cover(target);
    }

    // True whilst an anvil entity is falling through the column over the player.
    private boolean anvilOverhead() {
        AABB column = mc.player.getBoundingBox();
        column = new AABB(column.minX, column.maxY, column.minZ,
            column.maxX, column.maxY + WATCH_HEIGHT, column.maxZ);
        for (Entity entity : mc.level.getEntities((Entity) null, column,
            e -> e instanceof FallingBlockEntity falling
                && falling.getBlockState().getBlock() instanceof AnvilBlock)) {
            if (entity.getDeltaMovement().y <= 0) {
                return true;
            }
        }
        return false;
    }

    // True whilst an anvil block with nothing under it sits in the column within reach.
    private boolean anvilPlacedOverhead(BlockPos feet) {
        int top = FIRST_PLACED + (int) mc.player.blockInteractionRange();
        for (int i = FIRST_PLACED; i <= top; i++) {
            BlockPos pos = feet.above(i);
            if (BlockUtil.state(pos).getBlock() instanceof AnvilBlock
                && BlockUtil.state(pos.below()).isAir()) {
                return true;
            }
        }
        return false;
    }

    private void cover(BlockPos target) {
        int slot = BlockUtil.findBlastProofSlot();
        if (slot == -1) {
            slot = BlockUtil.findBlockSlot(block -> true);
        }
        if (slot == -1) {
            return;
        }
        slots.select(slot);
        if (BlockUtil.placeAny(target, rotate.isOn(), false)) {
            swing.getValue().swing();
        }
        slots.restore();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && roof != null) {
            event.getBatch().outlineBlock(roof, ROOF_COLOR, false);
        }
    }
}
