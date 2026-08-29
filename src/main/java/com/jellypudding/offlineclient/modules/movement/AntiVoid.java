package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.HoverDip;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AntiVoid extends Module {

    public enum Mode { CATCH, PLACE_BLOCK }

    // Fall speed that counts as a fall and not a step down.
    private static final double FALLING = -0.2;

    // The lift of a normal jump. Used for climbing out whilst caught.
    private static final double JUMP = 0.42;

    // Ticks between the dips that keep the flight kick away whilst caught.
    private static final int DIP_INTERVAL = 60;

    /**
     * Ticks of placing in a row before the catch takes over. Open void has
     * nothing to build against and every placement comes back as a ghost.
     */
    private static final int PLACE_LIMIT = 20;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What to do once the fall is spotted.", Mode.CATCH)
        .describe(Mode.CATCH, "Holds you still in the air. Press jump to climb whilst caught.")
        .describe(Mode.PLACE_BLOCK, "Puts a block from your hotbar under your feet.");
    private final NumberSetting depth = new NumberSetting("Depth",
        "Empty blocks below you that count as a void fall.", 12, 3, 40, 1, " blocks")
        .min(2).max(64);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Face the block being placed for the server to accept it.", true)
        .under(mode, Mode.PLACE_BLOCK);

    private final SlotSwap slots = new SlotSwap();
    private final HoverDip dip = new HoverDip();
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
        boolean wasCatching = catching;
        catching = false;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mc.player.getAbilities().flying || mc.player.isFallFlying()
            || mc.player.isPassenger() || mc.player.onGround()) {
            placeTries = 0;
            return;
        }
        // A caught player hangs still so the speed only starts the catch.
        Vec3 motion = mc.player.getDeltaMovement();
        if ((!wasCatching && motion.y > FALLING) || !voidBelow()) {
            placeTries = 0;
            return;
        }

        catching = true;
        if (!wasCatching) {
            dip.reset();
        }
        if (mode.is(Mode.PLACE_BLOCK) && placeTries < PLACE_LIMIT && placeUnderfoot()) {
            placeTries++;
            return;
        }
        hold(motion);
    }

    // Hangs in place. A jump lets go and the fall after it is caught again.
    private void hold(Vec3 motion) {
        if (mc.player.input.keyPresses.jump()) {
            mc.player.setDeltaMovement(motion.x, JUMP, motion.z);
            catching = false;
            return;
        }
        mc.player.setDeltaMovement(motion.x, 0, motion.z);
        dip.tick(DIP_INTERVAL);
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
        return true;
    }
}
