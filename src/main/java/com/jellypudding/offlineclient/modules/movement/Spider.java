package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// A wall the player pushes into is climbed and a risen ceiling can be hung from.
// Both keep a block within reach to dodge the vanilla flight kick.
public final class Spider extends Module {

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

    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast you go up the wall in blocks a tick.",
        0.2, 0.1, 0.5, 0.05, " blocks").min(0.01);
    private final BoolSetting holdOn = new BoolSetting("Hold on",
        "Keeps you in place on a wall when you stop climbing. Sneak to let go.", true);
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

    public Spider() {
        super("Spider", "Climb up any wall like a spider.", Category.MOVEMENT);
        addSettings(speed, holdOn, ceilings, autoRound);
        searchTags("wall climb", "ceiling");
    }

    @Override
    public String getSuffix() {
        return hanging ? "hanging" : null;
    }

    @Override
    protected void onDisable() {
        hanging = false;
        clinging = false;
        rounding = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean held = clinging;
        boolean hung = hanging;
        hanging = false;
        clinging = false;
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
        if (mc.player.horizontalCollision) {
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

    // Clinging only happens in the air. Sneak lets go.
    private boolean canCling() {
        return !mc.player.onGround() && !mc.player.isShiftKeyDown() && !mc.player.isInWater()
            && !mc.player.isInLava();
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
