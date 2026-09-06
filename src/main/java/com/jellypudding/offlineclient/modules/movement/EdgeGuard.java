package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Holds you on a ledge the way sneaking does but at full speed by simulating
// the run ahead a tick at a time. The clamp itself lives in LocalPlayerMixin and PlayerMixin.
public final class EdgeGuard extends Module {

    public enum Mode { HOLD, SNEAK }

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

    private static final int EDGE_BOX_COLOR = 0xFF4080FF;
    private static final int PLAYER_BOX_COLOR = 0xFF60E060;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What happens at an edge.", Mode.HOLD)
        .describe(Mode.HOLD, "Holds you at the edge at full speed.")
        .describe(Mode.SNEAK, "Presses sneak for you as you reach an edge the way a careful player would.");
    private final NumberSetting maxDrop = new NumberSetting("Max drop",
        "Edges you would fall further than this from stop you.",
        0.5, 0.5, 10, 0.5, " blocks");
    private final BoolSetting safeSneak = new BoolSetting("Safe sneak",
        "Also holds you at the edge in case the sneak comes too late.", true)
        .under(mode, Mode.SNEAK);
    private final BoolSetting sneakWhenSprinting = new BoolSetting("Sneak when sprinting",
        "Also sneaks whilst you hold the sprint key. Off lets a sprint run straight off.", true)
        .under(mode, Mode.SNEAK);
    private final NumberSetting edgeDistance = new NumberSetting("Edge distance",
        "How far before the edge the sneak starts.", 0.3, 0, 0.3, 0.01, " blocks")
        .min(0).max(0.3).under(mode, Mode.SNEAK);
    private final BoolSetting showEdgeBox = new BoolSetting("Show edge box",
        "Draws the box the edge check looks under.", false)
        .under(mode, Mode.SNEAK);
    private final BoolSetting showPlayerBox = new BoolSetting("Show player box",
        "Also draws your own box.", false)
        .under(showEdgeBox);

    private boolean sneaking;

    public EdgeGuard() {
        super("EdgeGuard", "Stops you from going over edges without the sneak slowdown.",
            Category.MOVEMENT);
        addSettings(mode, maxDrop, safeSneak, sneakWhenSprinting, edgeDistance, showEdgeBox,
            showPlayerBox);
        searchTags("safewalk", "safe walk", "edge", "ledge");
    }

    // Parkour leaps from the same lip EdgeGuard holds you at.
    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.EDGE;
    }

    @Override
    public String getSuffix() {
        return maxDrop.getValueString();
    }

    @Override
    protected void onDisable() {
        letGoOfSneak();
    }

    // True when the run ahead ends in a drop deeper than allowed.
    public boolean shouldGuard() {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        if (mode.is(Mode.SNEAK) && (!safeSneak.isOn() || sprintOverrides())) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        double limitY = box.minY - maxDrop.getValue() - TOLERANCE;
        return landsBelow(box, mc.player.getDeltaMovement(), mc.player.onGround(), limitY);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mode.is(Mode.SNEAK) || sprintOverrides()) {
            letGoOfSneak();
            return;
        }
        if (mc.player.onGround() && dropUnder(edgeBox())) {
            InputUtil.hold(mc.options.keyShift);
            sneaking = true;
        } else {
            letGoOfSneak();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!showEdgeBox.isOn() || !mode.is(Mode.SNEAK) || !inGame()) {
            return;
        }
        AABB box = EntityUtil.lerpedBox(mc.player, event.getPartialTicks());
        event.getBatch().outlineBox(shrink(box), EDGE_BOX_COLOR, false);
        if (showPlayerBox.isOn()) {
            event.getBatch().outlineBox(box, PLAYER_BOX_COLOR, false);
        }
    }

    private boolean sprintOverrides() {
        return !sneakWhenSprinting.isOn() && mc.options.keySprint.isDown();
    }

    private void letGoOfSneak() {
        if (sneaking) {
            InputUtil.release(mc.options.keyShift);
            sneaking = false;
        }
    }

    // The player box pulled in from the sides. Standing with only the rim of the
    // feet over the drop leaves nothing under it.
    private AABB edgeBox() {
        return shrink(mc.player.getBoundingBox());
    }

    private AABB shrink(AABB box) {
        double edge = edgeDistance.getValue();
        return box.inflate(-edge, 0, -edge);
    }

    // True when the fall under the box would be deeper than allowed.
    private boolean dropUnder(AABB box) {
        double depth = maxDrop.getValue() + TOLERANCE;
        return mc.level.noCollision(mc.player, box.expandTowards(0, -depth, 0));
    }

    // Plays the movement forward until the player lands or passes the limit. A
    // supported run keeps its speed then vanilla drag and gravity take over once airborne.
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
