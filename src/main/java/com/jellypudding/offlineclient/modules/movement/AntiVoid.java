package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AntiVoid extends Module {

    // Upward push per tick whilst catching.
    private static final double LIFT = 0.08;

    // Fall speed that counts as a fall and not a step down.
    private static final double FALLING = -0.2;

    /**
     * Ticks of placing in a row before the catch takes over. Open void has
     * nothing to build against and every placement comes back as a ghost.
     */
    private static final int PLACE_LIMIT = 20;

    public enum Mode {
        CATCH("Catch"),
        PLACE("Place block");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Catch holds you up. Place block puts a block under your feet.", Mode.CATCH);
    private final NumberSetting depth = new NumberSetting("Depth",
        "Empty blocks below you that count as a void fall.", 12, 3, 40, 1, " blocks")
        .min(2).max(64);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Face the block being placed for the server to accept it.", true)
        .visibleWhen(() -> mode.is(Mode.PLACE));

    private final SlotSwap slots = new SlotSwap();
    private boolean catching;
    private int placeTries;

    public AntiVoid() {
        super("AntiVoid", "Stops you falling into the void.", Category.MOVEMENT);
        addSettings(mode, depth, rotate);
        searchTags("void", "anti void", "no void");
    }

    @Override
    public String getSuffix() {
        return catching ? "catching" : null;
    }

    @Override
    protected void onDisable() {
        catching = false;
        placeTries = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        catching = false;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mc.player.getAbilities().flying || mc.player.isFallFlying()
            || mc.player.isPassenger() || mc.player.onGround()) {
            placeTries = 0;
            return;
        }
        Vec3 motion = mc.player.getDeltaMovement();
        if (motion.y > FALLING || !voidBelow()) {
            placeTries = 0;
            return;
        }

        catching = true;
        if (mode.is(Mode.PLACE) && placeTries < PLACE_LIMIT && placeUnderfoot()) {
            placeTries++;
            return;
        }
        mc.player.setDeltaMovement(motion.x, LIFT, motion.z);
        mc.player.resetFallDistance();
    }

    private boolean voidBelow() {
        AABB box = mc.player.getBoundingBox();
        int bottom = mc.level.getMinY();
        int startY = (int) Math.floor(box.minY);
        int limit = depth.getInt();

        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int i = 0; i <= limit; i++) {
                    int y = startY - i;
                    if (y < bottom) {
                        break;
                    }
                    if (BlockUtil.isSolid(new BlockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean placeUnderfoot() {
        int slot = BlockUtil.findBlockSlot();
        if (slot == -1) {
            return false;
        }
        BlockPos target = mc.player.blockPosition().below();
        if (!BlockUtil.isReplaceable(target)) {
            return false;
        }
        slots.select(slot);
        Direction support = BlockUtil.findPlaceSupport(target);
        boolean placed = support != null
            ? BlockUtil.place(target, support, rotate.isOn(), true)
            : BlockUtil.placeDirect(target, rotate.isOn(), true);
        slots.restore();
        if (!placed) {
            return false;
        }
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(motion.x, 0, motion.z);
        mc.player.resetFallDistance();
        return true;
    }
}
