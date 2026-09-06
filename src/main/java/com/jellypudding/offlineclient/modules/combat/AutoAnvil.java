package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.phys.shapes.CollisionContext;

// Places anvils in the air above an enemy.
// A landing anvil damages the helmet of whoever is under it.
public final class AutoAnvil extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 4, 1, 10, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Targets",
        TargetPriority.LOW_HEALTH);
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final NumberSetting height = new NumberSetting("Height",
        "How far above their feet the anvil goes.", 3, 2, 6, 1, " blocks");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between anvils.", 10, 0, 40, 1, " ticks");
    private final BoolSetting multiPlace = new BoolSetting("Multi place",
        "Fill every free spot from the height down to two above their head in one go.", true);
    private final BoolSetting trigger = new BoolSetting("Place trigger",
        "Put a button or a plate at their feet to break every anvil on landing.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the target has no helmet left.", false);
    private final BoolSetting closeMenu = new BoolSetting("Close menu",
        "Keep the repair screen shut when a click lands on a placed anvil.", true);
    private final BoolSetting render = new BoolSetting("Show spot",
        "Outline where the next anvil goes.", true);

    private final SlotSwap slots = new SlotSwap();
    private String targetName;
    private BlockPos spot;
    private int timer;

    public AutoAnvil() {
        super("AutoAnvil", "Drops anvils on an enemy to break their helmet.", Category.COMBAT);
        addSettings(targetRange, priority, placeRange, height, delay, multiPlace, trigger, rotate,
            toggleOff, closeMenu, render);
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
        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
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
        if (anvilSlot == -1) {
            slots.restore();
            return;
        }
        slots.select(anvilSlot);
        boolean placed = false;
        // The lowest spot still leaves the anvil a block to fall through.
        for (int y = height.getInt(); y >= 2; y--) {
            BlockPos pos = feet.above(y);
            if (BlockUtil.distanceTo(pos) > placeRange.getValue() || !BlockUtil.isReplaceable(pos)) {
                continue;
            }
            boolean turn = rotate.isOn() && !rotated;
            if (BlockUtil.placeAny(pos, turn, true)) {
                placed = true;
                rotated |= turn;
                if (!multiPlace.isOn()) {
                    break;
                }
            }
        }
        if (placed) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    // The server opens the repair menu for a click that lands on an anvil already down.
    // The menu is closed on the server too so the next click is not swallowed.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (closeMenu.isOn() && event.getPacket() instanceof ClientboundOpenScreenPacket packet
            && packet.getType() == MenuType.ANVIL && mc.player != null) {
            event.cancel();
            mc.player.connection.send(new ServerboundContainerClosePacket(packet.getContainerId()));
        }
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
