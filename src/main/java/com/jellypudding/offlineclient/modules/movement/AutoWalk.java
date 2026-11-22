package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.VehicleTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class AutoWalk extends Module {

    public enum Mode { SIMPLE, SMART }

    public enum Direction { FORWARDS, BACKWARDS, LEFT, RIGHT }

    private static final int LOOK_AHEAD = 16;
    private static final int RETARGET_TICKS = 40;
    private static final double GOAL_RADIUS = 3;

    private static final double BOAT_LOOK_TICKS = 10;
    private static final double BOAT_LEAST_LOOK = 2;
    private static final double BOAT_LOOK_STEP = 1;
    private static final double BOAT_SIDE = 0.5;

    private static final float BOAT_TURN = 4;

    private static final double BOAT_CLEARANCE = 0.1;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the walking is done.", Mode.SIMPLE)
        .describe(Mode.SIMPLE, "Holds the key and walks into whatever is in the way.")
        .describe(Mode.SMART, "Walks the way you face and finds a path round what is in the way.");
    private final EnumSetting<Direction> direction = new EnumSetting<>("Direction",
        "Which way you walk.", Direction.FORWARDS);
    private final BoolSetting autoSprint = new BoolSetting("Auto sprint",
        "Sprints instead of walking.", false);
    private final BoolSetting stopOnInput = new BoolSetting("Stop on input",
        "Switches off as soon as you press a movement key yourself.", false);
    private final BoolSetting stopOnHeightChange = new BoolSetting("Stop on height change",
        "Switches off as soon as you move up or down.", false)
        .under(mode, Mode.SIMPLE);
    private final BoolSetting stayInLoadedChunks = new BoolSetting("Stay in loaded chunks",
        "Stops walking whilst the chunk ahead has not loaded yet.", true);
    private final BoolSetting steerBoats = new BoolSetting("Steer boats",
        "A boat you row forwards holds its heading and turns away from a wall that comes close."
            + " It keeps an ice road by itself.", true);

    private final boolean[] wasHeld = new boolean[6];
    private KeyMapping held;

    private final Trip trip = new Trip();
    private int retarget;
    private float heading = Float.NaN;

    public AutoWalk() {
        super("AutoWalk", "Walks for you.", Category.MOVEMENT);
        addSettings(mode, direction, autoSprint, stopOnInput, stopOnHeightChange, stayInLoadedChunks,
            steerBoats);
        searchTags("auto walk", "path", "afk walk");
    }

    @Override
    public String getSuffix() {
        return direction.getValueString();
    }

    @Override
    protected void onEnable() {
        retarget = 0;
        heading = Float.NaN;
        if (!inGame()) {
            return;
        }
        List<KeyMapping> keys = movementKeys();
        for (int i = 0; i < keys.size(); i++) {
            wasHeld[i] = InputUtil.physicallyHeld(keys.get(i));
        }
    }

    // The key state only changes again on a real key event.
    @Override
    protected void onDisable() {
        letGo();
        trip.stop();
    }

    // The move of the tick is done and the old height still holds where the tick began.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (inGame() && mode.is(Mode.SIMPLE) && stopOnHeightChange.isOn() && mc.player.yo != mc.player.getY()) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean pressed = pressedMovementKey();
        if (stopOnInput.isOn() && pressed) {
            setEnabled(false);
            return;
        }
        if (stayInLoadedChunks.isOn() && chunkAheadMissing()) {
            letGo();
            trip.stop();
            Entity mover = mc.player.getRootVehicle();
            mover.setDeltaMovement(0, mover.getDeltaMovement().y, 0);
            return;
        }
        // A boat is rowed with the key. The pathfinder only knows the way on foot.
        if (mode.is(Mode.SMART) && !rows()) {
            letGo();
            smartTick();
            return;
        }
        trip.stop();
        KeyMapping wanted = keyFor(direction.getValue());
        if (held != wanted) {
            letGo();
        }
        held = wanted;
        held.setDown(true);
        if (autoSprint.isOn()) {
            return;
        }
        // A hand started sprint ends on key release.
        if (mc.player.isSprinting() && !InputUtil.physicallyHeld(mc.options.keyUp)
            && !Modules.enabled(Sprint.class)) {
            mc.player.setSprinting(false);
        }
    }

    private boolean rows() {
        return steerBoats.isOn() && mc.player.getControlledVehicle() instanceof AbstractBoat;
    }

    // A wall nearer on one side turns the heading away from it for good and the boat follows a
    // bend.
    @Subscribe
    private void onVehicleTick(VehicleTickEvent event) {
        boolean byHand = InputUtil.physicallyHeld(mc.options.keyLeft)
            || InputUtil.physicallyHeld(mc.options.keyRight);
        if (!(event.getVehicle() instanceof AbstractBoat boat) || !steerBoats.isOn() || byHand
            || held != mc.options.keyUp || !boat.isLocalInstanceAuthoritative()) {
            heading = Float.NaN;
            return;
        }
        if (Float.isNaN(heading)) {
            heading = boat.getYRot();
        }
        double reach = Math.max(BOAT_LEAST_LOOK, boat.getDeltaMovement().horizontalDistance() * BOAT_LOOK_TICKS);
        double left = wallAhead(boat, heading - 90, reach);
        double right = wallAhead(boat, heading + 90, reach);
        if (left < right) {
            heading += BOAT_TURN;
        } else if (right < left) {
            heading -= BOAT_TURN;
        }
        boat.setYRot(heading);
    }

    private double wallAhead(AbstractBoat boat, float side, double reach) {
        AABB box = boat.getBoundingBox();
        AABB shifted = box.setMinY(box.minY + BOAT_CLEARANCE).move(Vec3.directionFromRotation(0, side).scale(BOAT_SIDE));
        Vec3 ahead = Vec3.directionFromRotation(0, heading);
        for (double far = BOAT_LOOK_STEP; far <= reach; far += BOAT_LOOK_STEP) {
            if (!mc.level.noCollision(boat, shifted.move(ahead.scale(far)))) {
                return far;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private void smartTick() {
        if (--retarget <= 0 || !trip.active()) {
            retarget = RETARGET_TICKS;
            trip.walker().turn(PathWalker.Turn.NONE).sprint(autoSprint.isOn());
            trip.start(aheadOf(mc.player.position()), GOAL_RADIUS);
        }
        trip.tick();
    }

    private BlockPos aheadOf(Vec3 from) {
        float yaw = mc.player.getYRot() + switch (direction.getValue()) {
            case FORWARDS -> 0;
            case BACKWARDS -> 180;
            case LEFT -> -90;
            case RIGHT -> 90;
        };
        Vec3 heading = Vec3.directionFromRotation(0, yaw).scale(LOOK_AHEAD);
        return BlockPos.containing(from.add(heading));
    }

    // Sprint is set late in the tick. The player tick clears sprint set any earlier.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || !autoSprint.isOn() || mode.is(Mode.SMART)) {
            return;
        }
        if (!mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }
    }

    private KeyMapping keyFor(Direction direction) {
        return switch (direction) {
            case FORWARDS -> mc.options.keyUp;
            case BACKWARDS -> mc.options.keyDown;
            case LEFT -> mc.options.keyLeft;
            case RIGHT -> mc.options.keyRight;
        };
    }

    private void letGo() {
        if (held != null) {
            InputUtil.release(held);
            held = null;
        }
    }

    private boolean pressedMovementKey() {
        Screen screen = mc.gui.screen();
        InvWalk invWalk = Modules.active(InvWalk.class);
        boolean listen = screen == null || (invWalk != null && invWalk.allows(screen));
        List<KeyMapping> keys = movementKeys();
        boolean pressed = false;
        for (int i = 0; i < keys.size(); i++) {
            boolean down = InputUtil.physicallyHeld(keys.get(i));
            if (down && !wasHeld[i] && listen) {
                pressed = true;
            }
            wasHeld[i] = down;
        }
        return pressed;
    }

    private List<KeyMapping> movementKeys() {
        return List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
            mc.options.keyRight, mc.options.keyShift, mc.options.keyJump);
    }

    private boolean chunkAheadMissing() {
        Entity mover = mc.player.getRootVehicle();
        Vec3 motion = mover.getDeltaMovement();
        return !LoadedChunks.has(mover.getX() + motion.x * 2, mover.getZ() + motion.z * 2);
    }
}
