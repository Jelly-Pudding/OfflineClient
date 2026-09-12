package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Stops you dead over a hole and drops you in. Meant for getting into a hole under fire.
// A hole is one block wide with a floor and four walls.
public final class HoleSnap extends Module {

    public enum Holes { BLAST_PROOF, ANY }

    // How hard one tick may pull towards the middle of the hole.
    private static final double CENTRE_PULL = 0.05;

    private final EnumSetting<Holes> holes = new EnumSetting<>("Holes",
        "Which holes count.", Holes.BLAST_PROOF)
        .describe(Holes.BLAST_PROOF, "Only holes walled with obsidian or bedrock. The ones a crystal cannot open.")
        .describe(Holes.ANY, "Any one block hole with solid walls and a floor.");
    private final NumberSetting maxHeight = new NumberSetting("Max height",
        "How far below you a hole is still snapped to.", 10, 1, 20, 1, " blocks");
    private final NumberSetting minPitch = new NumberSetting("Min pitch",
        "Only snaps whilst you look down at least this far. Zero is level.", 0, -90, 90, 5, " degrees");
    private final BoolSetting pull = new BoolSetting("Pull down",
        "Also pulls you down into the hole.", false);
    private final NumberSetting pullSpeed = new NumberSetting("Pull speed",
        "Blocks a tick the pull adds.", 0.3, 0.1, 5, 0.1, " blocks")
        .under(pull);
    private final BoolSetting cancelJump = new BoolSetting("Cancel jump",
        "Jumping is ignored whilst a hole is under you.", false);

    private boolean overHole;
    private BlockPos holeStoodIn;

    public HoleSnap() {
        super("HoleSnap", "Stops your movement over a hole and drops you straight in.",
            Category.MOVEMENT);
        addSettings(holes, maxHeight, minPitch, pull, pullSpeed, cancelJump);
        searchTags("anchor", "hole", "crystal pvp");
    }

    @Override
    public String getSuffix() {
        return overHole ? "over hole" : null;
    }

    @Override
    protected void onEnable() {
        overHole = false;
        holeStoodIn = null;
    }

    // Read by Speed. Nothing else may push the player whilst a hole has them.
    public boolean holdsMovement() {
        return isEnabled() && overHole;
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
        if (BlockUtil.blocksMotion(BlockUtil.state(pos)) || !wall(pos.below())) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!wall(pos.relative(side))) {
                return false;
            }
        }
        return true;
    }

    private boolean wall(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (holes.is(Holes.ANY)) {
            return BlockUtil.blocksMotion(state);
        }
        return state.getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF;
    }
}
