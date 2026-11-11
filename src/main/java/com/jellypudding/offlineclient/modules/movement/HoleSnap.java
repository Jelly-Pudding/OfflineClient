package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.WholeMove;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

// Stops you dead over a hole and drops you in. Meant for getting into a hole under fire.
// A hole is one or two blocks wide with a floor and walls all round.
public final class HoleSnap extends Module {

    public enum HoleKind { BLAST_PROOF, ANY }

    // How hard one tick may pull towards the middle of the hole.
    private static final double CENTRE_PULL = 0.05;

    // A walk to a hole that takes longer than this has gone wrong.
    private static final int SEEK_TICKS = 60;

    // How far apart the floor is looked at along a walk to a hole.
    private static final double FLOOR_STEP = 0.25;

    private final EnumSetting<HoleKind> holes = new EnumSetting<>("Holes",
        "Which holes count.", HoleKind.BLAST_PROOF)
        .describe(HoleKind.BLAST_PROOF, "Only holes walled with obsidian or bedrock. The ones a crystal cannot open.")
        .describe(HoleKind.ANY, "Any hole one or two blocks wide with solid walls and a floor.");
    private final NumberSetting seekRange = new NumberSetting("Seek range",
        "Switching on walks you over level floor to the nearest hole within this many blocks that is"
            + " no more than three blocks down and takes it wherever you look. Nought only takes a hole"
            + " right under you.", 0, 0, 8, 1, " blocks");
    private final BoolSetting switchOff = new BoolSetting("Switch off in hole",
        "Switches HoleSnap off once you stand in a hole.", false);
    private final NumberSetting minHeight = new NumberSetting("Min height",
        "How far below you a hole has to be. One takes a hole you walk over.", 1, 1, 20, 1, " blocks");
    private final NumberSetting maxHeight = new NumberSetting("Max height",
        "How far below you a hole is still snapped to.", 10, 1, 20, 1, " blocks");
    private final NumberSetting minPitch = new NumberSetting("Min pitch",
        "Only snaps whilst you look down at least this far. Zero is level.", 0, -90, 90, 5, " degrees");
    private final BoolSetting pull = new BoolSetting("Pull down",
        "Also pulls you down into the hole.", false);
    private final NumberSetting pullSpeed = new NumberSetting("Pull speed",
        "How much downward speed the pull adds each tick.", 0.3, 0.1, 5, 0.1, " blocks")
        .under(pull);
    private final BoolSetting cancelJump = new BoolSetting("Cancel jump",
        "Jumping is ignored whilst a hole is under you.", false);

    private boolean overHole;
    private BlockPos holeStoodIn;
    // The column of the hole being walked to. Null whilst none is.
    private BlockPos sought;
    private int seekTicks;

    public HoleSnap() {
        super("HoleSnap", "Stops your movement over a hole and drops you straight in.",
            Category.MOVEMENT);
        addSettings(holes, seekRange, switchOff, minHeight, maxHeight, minPitch, pull, pullSpeed, cancelJump);
        searchTags("anchor", "hole", "crystal pvp");
    }

    @Override
    public String getSuffix() {
        if (sought != null) {
            return "seeking";
        }
        return overHole ? "over hole" : null;
    }

    @Override
    protected void onEnable() {
        overHole = false;
        holeStoodIn = null;
        seekTicks = 0;
        sought = inGame() && !mc.player.isPassenger() && mc.player.onGround() ? nearestHole() : null;
    }

    @Override
    protected void onDisable() {
        sought = null;
    }

    // True whilst HoleSnap is on and has the player over a hole or walks them to one. Nothing else
    // may push or stop the player then.
    public static boolean holds() {
        HoleSnap holeSnap = Modules.active(HoleSnap.class);
        return holeSnap != null && (holeSnap.overHole || holeSnap.sought != null);
    }

    // Read by LocalPlayerMixin before a jump.
    public boolean cancelsJump() {
        return isEnabled() && cancelJump.isOn() && overHole;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        overHole = false;
        BlockPos feet = mc.player.blockPosition();
        if (holeStoodIn != null && !sameColumn(holeStoodIn, feet)) {
            holeStoodIn = null;
        }
        if (mc.player.isPassenger() || mc.player.getAbilities().flying) {
            sought = null;
            return;
        }
        if (isHole(feet)) {
            holeStoodIn = feet;
            sought = null;
            if (switchOff.isOn()) {
                setEnabled(false);
            }
            return;
        }
        if (seeks(feet)) {
            return;
        }
        // Climbing out of the hole must not drag you straight back in.
        if (holeStoodIn != null) {
            return;
        }
        // A hole walked to is taken wherever the player looks.
        if (sought == null && mc.player.getXRot() < minPitch.getValue()) {
            return;
        }
        BlockPos hole = holeBelow(feet);
        if (hole == null) {
            sought = null;
            return;
        }
        overHole = true;
        double dx = Mth.clamp(hole.getX() + 0.5 - mc.player.getX(), -CENTRE_PULL, CENTRE_PULL);
        double dz = Mth.clamp(hole.getZ() + 0.5 - mc.player.getZ(), -CENTRE_PULL, CENTRE_PULL);
        if (pull.isOn()) {
            mc.player.addDeltaMovement(new Vec3(0, -pullSpeed.getValue(), 0));
        }
        WholeMove.set(dx, dz);
    }

    // Walks towards the sought hole at a sprint. False whilst there is no walk to make. The
    // snap takes the player once they stand over the hole.
    private boolean seeks(BlockPos feet) {
        if (sought == null || sameColumn(sought, feet)) {
            return false;
        }
        if (++seekTicks > SEEK_TICKS || mc.player.horizontalCollision || !seekable(sought.atY(feet.getY()))) {
            sought = null;
            return false;
        }
        Vec3 pace = wayTo(sought).normalize()
            .scale(MovementUtil.withSpeedEffects(mc.player, MovementUtil.SPRINT_SPEED));
        WholeMove.set(pace.x, pace.z);
        return true;
    }

    // The nearest column in range the player can be walked to for the hole under it. Null
    // when there is none.
    private BlockPos nearestHole() {
        BlockPos feet = mc.player.blockPosition();
        int range = seekRange.getInt();
        BlockPos nearest = null;
        double best = Mth.square(seekRange.getValue());
        for (BlockPos column : BlockPos.betweenClosed(feet.offset(-range, 0, -range), feet.offset(range, 0, range))) {
            double distance = wayTo(column).lengthSqr();
            if (distance <= best && !column.equals(feet) && seekable(column)) {
                best = distance;
                nearest = column.immutable();
            }
        }
        return nearest;
    }

    // True when the column has a hole under it that is no drop to get hurt by and the walk
    // to it is clear with a floor all the way.
    private boolean seekable(BlockPos column) {
        BlockPos hole = holeBelow(column);
        return hole != null && column.getY() - hole.getY() <= DamageUtil.safeFall(mc.player) && walkable(column);
    }

    private static boolean sameColumn(BlockPos one, BlockPos other) {
        return one.getX() == other.getX() && one.getZ() == other.getZ();
    }

    private Vec3 wayTo(BlockPos column) {
        return new Vec3(column.getX() + 0.5 - mc.player.getX(), 0, column.getZ() + 0.5 - mc.player.getZ());
    }

    // The floor is looked at under the middle of the player all along the straight line
    // there. The column of the hole itself is the one place with none.
    private boolean walkable(BlockPos column) {
        Vec3 way = wayTo(column);
        if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(way))) {
            return false;
        }
        int steps = Mth.ceil(way.length() / FLOOR_STEP);
        for (int step = 0; step < steps; step++) {
            BlockPos over = BlockPos.containing(mc.player.position().add(way.scale((double) step / steps)));
            if (!sameColumn(over, column) && !standable(over.atY(column.getY()).below())) {
                return false;
            }
        }
        return true;
    }

    private static boolean standable(BlockPos floor) {
        BlockState state = BlockUtil.state(floor);
        return BlockUtil.blocksMotion(state) && !BlockUtil.harmful(state);
    }

    // The first hole straight down through open air or null.
    private BlockPos holeBelow(BlockPos feet) {
        BlockPos pos = feet;
        for (int depth = 1; depth <= maxHeight.getInt(); depth++) {
            pos = pos.below();
            if (pos.getY() <= mc.level.getMinY() || BlockUtil.blocksMotion(BlockUtil.state(pos))) {
                return null;
            }
            if (depth >= minHeight.getInt() && isHole(pos)) {
                return pos;
            }
        }
        return null;
    }

    // An open block in a hole one or two blocks wide.
    private boolean isHole(BlockPos pos) {
        return !BlockUtil.isSolid(pos)
            && !Holes.hole(pos, this::wall, Predicate.not(BlockUtil::isSolid), true).isEmpty();
    }

    private boolean wall(BlockPos pos) {
        Holes.Wall wall = Holes.wallOf(BlockUtil.state(pos));
        return holes.is(HoleKind.ANY) ? wall != Holes.Wall.OPEN : wall.holds();
    }
}
