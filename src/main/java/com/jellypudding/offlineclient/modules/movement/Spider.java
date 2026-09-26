package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// A wall the player pushes into is climbed and a risen ceiling can be hung from.
// Both keep a block within reach to dodge the vanilla flight kick.
public final class Spider extends Module {

    public enum SneakAction { CLIMB_DOWN, LET_GO }

    // How far past the box a ceiling or a wall is felt for.
    private static final double PROBE = 0.05;

    // Pace along a ceiling in blocks a tick. About walking speed.
    private static final double CEILING_PACE = 0.2;

    // How far past the box a ceiling edge can still be held.
    private static final double GRIP_REACH = 0.3;

    // How hard a held edge pulls the player in against it.
    private static final double GRIP_PULL = 0.1;

    // The most upward speed kept once nothing is held. It lifts you over the top of a
    // wall and no further.
    private static final double RELEASE_SPEED = 0.2;

    // The server banks every block you are lowered and charges it on landing. A climb
    // down rises this much for a tick before the bank reaches the drop that hurts.
    private static final double WIPE_RISE = 0.01;
    private static final double SAFE_DROP = 2.5;

    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast you go up the wall in blocks a tick.",
        0.2, 0.1, 0.5, 0.05, " blocks").min(0.01);
    private final EnumSetting<SneakAction> sneak = new EnumSetting<>("Sneak",
        "What sneaking does on a wall.", SneakAction.CLIMB_DOWN)
        .describe(SneakAction.CLIMB_DOWN, "Sneaking lowers you down the wall without any fall damage.")
        .describe(SneakAction.LET_GO, "Sneaking lets go of the wall.");
    private final NumberSetting descentSpeed = new NumberSetting("Descent speed",
        "How fast sneaking lowers you down the wall in blocks a tick.",
        0.15, 0.05, 0.5, 0.05, " blocks").min(0.01).under(sneak, SneakAction.CLIMB_DOWN);
    private final BoolSetting holdOn = new BoolSetting("Hold on",
        "Keeps you in place on a wall when you stop climbing.", true);
    private final BoolSetting ceilings = new BoolSetting("Ceilings",
        "Hang from ceilings and walk along them. Sneak to drop.", false);
    private final BoolSetting autoRound = new BoolSetting("Auto climb edges",
        "Climbs round a ceiling edge without you holding jump.", false)
        .under(ceilings);

    private boolean hanging;
    // Something held the player up last tick.
    private boolean clinging;
    // Climbing round the edge of a ceiling just hung from.
    private boolean rounding;
    // How far the climb down has lowered you since the last rise.
    private double lowered;
    private boolean lowering;

    public Spider() {
        super("Spider", "Climb up and down any wall like a spider.", Category.MOVEMENT);
        addSettings(speed, sneak, descentSpeed, holdOn, ceilings, autoRound);
        searchTags("wall climb", "ceiling", "climb down");
    }

    @Override
    public String getSuffix() {
        if (hanging) {
            return "hanging";
        }
        return lowering ? "down" : null;
    }

    @Override
    protected void onDisable() {
        hanging = false;
        clinging = false;
        rounding = false;
        lowering = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean held = clinging;
        boolean hung = hanging;
        boolean wasLowering = lowering;
        hanging = false;
        clinging = false;
        lowering = false;
        if (!inGame() || mc.player.isPassenger()) {
            rounding = false;
            return;
        }
        if (ceilings.isOn() && hang()) {
            rounding = false;
            clinging = true;
            return;
        }
        if (ceilings.isOn() && (hung || rounding) && roundEdge()) {
            rounding = true;
            clinging = true;
            return;
        }
        rounding = false;
        boolean onWall = mc.player.horizontalCollision || (held && besideWall());
        if (mc.player.isShiftKeyDown() && airborne() && onWall) {
            if (sneak.is(SneakAction.CLIMB_DOWN)) {
                climbDown(wasLowering);
                clinging = true;
                lowering = true;
            } else if (held) {
                release();
            }
        } else if (mc.player.horizontalCollision) {
            climb();
            clinging = true;
        } else if (held && holdOn.isOn() && canCling() && besideWall()) {
            stay();
            clinging = true;
        } else if (held) {
            release();
        }
    }

    private void climb() {
        Vec3 velocity = mc.player.getDeltaMovement();
        // Faster upward motion from jumps and boosts is left alone.
        if (velocity.y >= speed.getValue()) {
            return;
        }
        mc.player.setDeltaMovement(velocity.x, speed.getValue(), velocity.z);
    }

    // The first tick down always rises. A fall taken before grabbing the wall is still
    // banked and that rise wipes it.
    private void climbDown(boolean wasLowering) {
        Vec3 velocity = mc.player.getDeltaMovement();
        double step = descentSpeed.getValue();
        if (!wasLowering || lowered + step > SAFE_DROP) {
            mc.player.setDeltaMovement(velocity.x, WIPE_RISE, velocity.z);
            lowered = 0;
        } else {
            mc.player.setDeltaMovement(velocity.x, -step, velocity.z);
            lowered += step;
        }
        // The client keeps its own count and would play a hard landing at the bottom.
        mc.player.resetFallDistance();
    }

    // Keeps pushing up into a ceiling. The collision holds the player against it.
    // The keys then move the player along it at walking pace.
    private boolean hang() {
        if (!canCling() || !ceilingAbove()) {
            return false;
        }
        hanging = true;
        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * CEILING_PACE, speed.getValue(), heading.z * CEILING_PACE);
        return true;
    }

    private void stay() {
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, 0, velocity.z);
    }

    // Past the edge of a ceiling the block just left is held from the side. Jump
    // climbs its face onto the top of it or up to a higher ceiling.
    private boolean roundEdge() {
        boolean rising = autoRound.isOn() || mc.player.input.keyPresses.jump();
        if (!canCling() || (!rising && !holdOn.isOn())) {
            return false;
        }
        Vec3 pull = pullTowardsEdge();
        if (pull == null) {
            return false;
        }
        mc.player.setDeltaMovement(pull.x * GRIP_PULL, rising ? speed.getValue() : 0, pull.z * GRIP_PULL);
        return true;
    }

    // The flat direction to the nearest block within reach. Null when none is close.
    private Vec3 pullTowardsEdge() {
        AABB box = mc.player.getBoundingBox();
        AABB reach = box.inflate(GRIP_REACH, 0, GRIP_REACH).expandTowards(0, GRIP_REACH, 0);
        Vec3 centre = box.getCenter();
        Vec3 nearest = null;
        double best = Double.MAX_VALUE;
        for (VoxelShape shape : mc.level.getBlockCollisions(mc.player, reach)) {
            AABB block = shape.bounds();
            Vec3 point = new Vec3(Math.clamp(centre.x, block.minX, block.maxX), centre.y,
                Math.clamp(centre.z, block.minZ, block.maxZ));
            double distance = point.distanceToSqr(centre);
            if (distance < best) {
                best = distance;
                nearest = point;
            }
        }
        if (nearest == null) {
            return null;
        }
        Vec3 flat = nearest.subtract(centre).multiply(1, 0, 1);
        return flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : flat.normalize();
    }

    // The climb leaves its upward speed behind. Kept whole a fast climb would throw
    // the player high over the top of the wall.
    private void release() {
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y > RELEASE_SPEED) {
            mc.player.setDeltaMovement(velocity.x, RELEASE_SPEED, velocity.z);
        }
    }

    // Clinging only happens in the air.
    private boolean airborne() {
        return !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava();
    }

    // Sneak lets go of a ceiling and takes over on a wall.
    private boolean canCling() {
        return airborne() && !mc.player.isShiftKeyDown();
    }

    private boolean besideWall() {
        return !mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().inflate(PROBE, -PROBE, PROBE));
    }

    private boolean ceilingAbove() {
        return !mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().move(0, PROBE, 0));
    }
}
