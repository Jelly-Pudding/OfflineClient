package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Holds you on a ledge the way sneaking does but at full speed. Where the
 * run ahead would land is simulated a tick at a time. A shallow step that
 * leads straight into a long drop still counts. The clamp itself lives in
 * LocalPlayerMixin and PlayerMixin.
 */
public final class EdgeGuard extends Module {

    // Vanilla air physics for a player.
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final double AIR_FRICTION = 0.91;
    private static final double AIR_ACCELERATION = 0.02;
    private static final double SPRINT_AIR_BONUS = 1.3;

    // Ticks of flight to simulate. A ten block fall is over well inside this.
    private static final int LOOKAHEAD_TICKS = 40;

    // Depth probed under the feet to tell a supported box from a falling one.
    private static final double GROUND_PROBE = 0.05;

    // Lets a drop of exactly the allowed depth through.
    private static final double TOLERANCE = 1e-4;

    private final NumberSetting maxDrop = new NumberSetting("Max drop",
        "Edges you would fall further than this from stop you.",
        0.5, 0.5, 10, 0.5, " blocks");

    public EdgeGuard() {
        super("EdgeGuard", "Stops you from going over edges without the sneak slowdown.",
            Category.MOVEMENT);
        addSettings(maxDrop);
        searchTags("safewalk", "safe walk", "edge", "ledge");
    }

    @Override
    public String getSuffix() {
        return maxDrop.getValueString();
    }

    // True when the run ahead ends in a drop deeper than allowed.
    public boolean shouldGuard() {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        double limitY = box.minY - maxDrop.getValue() - TOLERANCE;
        return landsBelow(box, mc.player.getDeltaMovement(), mc.player.onGround(), limitY);
    }

    /**
     * Plays the movement forward until the player lands or passes the limit.
     * Whilst still supported the run carries on at its current speed. Once
     * airborne the vanilla drag and gravity take over with the keys still
     * pushing.
     */
    private boolean landsBelow(AABB box, Vec3 velocity, boolean supported, double limitY) {
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;
        Vec3 push = airPush();

        for (int tick = 0; tick < LOOKAHEAD_TICKS; tick++) {
            AABB ahead = box.move(vx, 0, vz);
            if (!mc.level.noCollision(mc.player, ahead)) {
                // A wall ends the run. On a ledge that means staying on it.
                if (supported) {
                    return false;
                }
                vx = 0;
                vz = 0;
                ahead = box;
            }
            box = ahead;
            if (supported) {
                if (hasGround(box)) {
                    continue;
                }
                // The edge is passed. The first fall tick carries the gravity already stored.
                supported = false;
                vy = -GRAVITY * AIR_DRAG;
            }

            AABB dropped = box.move(0, vy, 0);
            if (vy < 0 && !mc.level.noCollision(mc.player, dropped)) {
                // Landed within this tick's drop. Fine if that ground is above the limit.
                if (box.minY + vy >= limitY) {
                    return false;
                }
                return mc.level.noCollision(mc.player, box.expandTowards(0, limitY - box.minY, 0));
            }
            box = dropped;
            if (box.minY < limitY) {
                return true;
            }
            vx = vx * AIR_FRICTION + push.x;
            vz = vz * AIR_FRICTION + push.z;
            vy = (vy - GRAVITY) * AIR_DRAG;
        }
        return false;
    }

    private boolean hasGround(AABB box) {
        return !mc.level.noCollision(mc.player, box.expandTowards(0, -GROUND_PROBE, 0));
    }

    // What the held keys add to the speed each tick in the air.
    private Vec3 airPush() {
        double strength = AIR_ACCELERATION * (mc.player.isSprinting() ? SPRINT_AIR_BONUS : 1);
        return MovementUtil.inputDirection().scale(strength);
    }
}
