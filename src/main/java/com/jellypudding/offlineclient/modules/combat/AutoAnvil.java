package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Places anvils in the air above an enemy. A landing anvil damages the helmet
 * of whoever is under it.
 */
public final class AutoAnvil extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 4, 1, 10, 0.5, " blocks");
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final NumberSetting height = new NumberSetting("Height",
        "How far above their feet the anvil goes.", 3, 2, 6, 1, " blocks");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between anvils.", 10, 0, 40, 1, " ticks");
    private final BoolSetting trigger = new BoolSetting("Place trigger",
        "Put a button or a plate at their feet to break every anvil on landing.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the target has no helmet left.", false);
    private final BoolSetting render = new BoolSetting("Show spot",
        "Outline where the next anvil goes.", true);

    private final SlotSwap slots = new SlotSwap();
    private String targetName;
    private BlockPos spot;
    private int timer;

    public AutoAnvil() {
        super("AutoAnvil", "Drops anvils on an enemy to break their helmet.", Category.COMBAT);
        addSettings(targetRange, placeRange, height, delay, trigger, rotate, toggleOff, render);
        searchTags("anvil", "helmet", "armour break");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targetName = null;
        spot = null;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        targetName = null;
        spot = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        spot = null;
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            slots.restore();
            return;
        }
        if (toggleOff.isOn() && target.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            slots.restore();
            setEnabled(false);
            return;
        }

        BlockPos feet = target.blockPosition();
        BlockPos above = feet.above(height.getInt());
        if (!clearPath(feet, above)) {
            slots.restore();
            return;
        }
        spot = above;
        if (timer > 0) {
            timer--;
            return;
        }

        // Only the first placement of the tick turns.
        boolean rotated = placeTrigger(feet);

        int anvilSlot = BlockUtil.findBlockSlot(block -> block instanceof AnvilBlock);
        if (anvilSlot == -1 || BlockUtil.distanceTo(above) > placeRange.getValue()) {
            slots.restore();
            return;
        }
        slots.select(anvilSlot);
        boolean turn = rotate.isOn() && !rotated;
        boolean placed = BlockUtil.placeAny(above, turn, true);
        if (placed) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    private boolean placeTrigger(BlockPos feet) {
        if (!trigger.isOn() || !BlockUtil.isReplaceable(feet)
            || BlockUtil.distanceTo(feet) > placeRange.getValue()) {
            return false;
        }
        Direction support = BlockUtil.findPlaceSupport(feet);
        if (support == null) {
            return false;
        }
        int slot = BlockUtil.findBlockSlot(block ->
            block instanceof ButtonBlock || block instanceof BasePressurePlateBlock);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        BlockUtil.place(feet, support, rotate.isOn(), true);
        return rotate.isOn();
    }

    // The anvil needs the whole column free to reach their head.
    private boolean clearPath(BlockPos feet, BlockPos above) {
        for (int y = feet.getY() + 1; y <= above.getY(); y++) {
            if (!BlockUtil.isReplaceable(new BlockPos(feet.getX(), y, feet.getZ()))) {
                return false;
            }
        }
        return mc.level.isUnobstructed(Blocks.ANVIL.defaultBlockState(), above, CollisionContext.empty());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && spot != null) {
            event.getBatch().outlineBlock(spot, 0xFFB0B0C0, false);
        }
    }
}
