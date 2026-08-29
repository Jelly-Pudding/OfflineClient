package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// Stops you dead over a hole and drops you in. Meant for getting into a hole under fire.
public final class HoleSnap extends Module {

    // How hard one tick may pull towards the middle of the hole.
    private static final double CENTRE_PULL = 0.05;

    private final NumberSetting maxHeight = new NumberSetting("Max height",
        "How far below you a hole is still snapped to.", 10, 1, 20, 1, " blocks");
    private final NumberSetting minPitch = new NumberSetting("Min pitch",
        "Only snaps whilst you look down at least this far.", 0, -90, 90, 5, "°");
    private final BoolSetting pull = new BoolSetting("Pull down",
        "Also pulls you down into the hole.", false);
    private final NumberSetting pullSpeed = new NumberSetting("Pull speed",
        "Blocks a tick the pull adds.", 0.3, 0.1, 5, 0.1, "")
        .under(pull);
    private final BoolSetting cancelJump = new BoolSetting("Cancel jump",
        "Jumping is ignored whilst a hole is under you.", false);

    private boolean overHole;
    private BlockPos holeStoodIn;

    public HoleSnap() {
        super("HoleSnap", "Stops your movement over a hole so you drop straight in.",
            Category.MOVEMENT);
        addSettings(maxHeight, minPitch, pull, pullSpeed, cancelJump);
        searchTags("anchor", "hole", "crystal pvp");
    }

    @Override
    protected void onEnable() {
        overHole = false;
        holeStoodIn = null;
    }

    // Read by LocalPlayerMixin before a jump.
    public boolean cancelsJump() {
        return isEnabled() && cancelJump.isOn() && overHole;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        overHole = false;
        if (!inGame() || mc.player.isPassenger()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        if (isHole(feet)) {
            holeStoodIn = feet;
            return;
        }
        // Climbing out of the hole must not drag you straight back in.
        if (holeStoodIn != null) {
            if (holeStoodIn.getX() == feet.getX() && holeStoodIn.getZ() == feet.getZ()) {
                return;
            }
            holeStoodIn = null;
        }
        if (mc.player.getXRot() < minPitch.getValue()) {
            return;
        }
        BlockPos hole = holeBelow(feet);
        if (hole == null) {
            return;
        }
        overHole = true;
        Vec3 velocity = mc.player.getDeltaMovement();
        double dx = Mth.clamp(hole.getX() + 0.5 - mc.player.getX(), -CENTRE_PULL, CENTRE_PULL);
        double dz = Mth.clamp(hole.getZ() + 0.5 - mc.player.getZ(), -CENTRE_PULL, CENTRE_PULL);
        double dy = velocity.y - (pull.isOn() ? pullSpeed.getValue() : 0);
        mc.player.setDeltaMovement(dx, dy, dz);
    }

    // The first hole straight down through open air or null.
    private BlockPos holeBelow(BlockPos feet) {
        BlockPos pos = feet;
        for (int i = 0; i < maxHeight.getInt(); i++) {
            pos = pos.below();
            if (pos.getY() <= mc.level.getMinY() || BlockUtil.blocksMotion(BlockUtil.state(pos))) {
                return null;
            }
            if (isHole(pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean isHole(BlockPos pos) {
        if (!blastProof(pos.below())) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!blastProof(pos.relative(side))) {
                return false;
            }
        }
        return true;
    }

    private boolean blastProof(BlockPos pos) {
        return BlockUtil.state(pos).getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF;
    }
}
