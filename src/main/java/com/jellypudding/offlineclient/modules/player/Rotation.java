package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.Direction;

// A block that turns by the look of whoever places it goes down by the look the server holds.
// Stairs and observers follow that look and pistons and dispensers face against it.
public final class Rotation extends Module {

    public enum Lock { OFF, FIXED, SNAP }

    public enum Facing {
        OFF(null), NORTH(Direction.NORTH), EAST(Direction.EAST), SOUTH(Direction.SOUTH), WEST(Direction.WEST),
        UP(Direction.UP), DOWN(Direction.DOWN);

        private final Direction way;

        Facing(Direction way) {
            this.way = way;
        }
    }

    private static final float YAW_SNAP = 45;
    private static final float PITCH_SNAP = 30;

    private final EnumSetting<Lock> yawLock = new EnumSetting<>("Yaw",
        "How the yaw is held.", Lock.FIXED)
        .describe(Lock.OFF, "The yaw is left alone.")
        .describe(Lock.FIXED, "Holds the yaw at the angle below.")
        .describe(Lock.SNAP, "Rounds the yaw to the nearest 45 degrees.");
    private final NumberSetting yaw = new NumberSetting("Yaw angle",
        "Degrees to hold the yaw at.", 0, -180, 180, 5, "°")
        .under(yawLock, Lock.FIXED);
    private final EnumSetting<Lock> pitchLock = new EnumSetting<>("Pitch",
        "How the pitch is held.", Lock.FIXED)
        .describe(Lock.OFF, "The pitch is left alone.")
        .describe(Lock.FIXED, "Holds the pitch at the angle below.")
        .describe(Lock.SNAP, "Rounds the pitch to the nearest 30 degrees.");
    private final NumberSetting pitch = new NumberSetting("Pitch angle",
        "Degrees to hold the pitch at. Down is positive.", 0, -90, 90, 5, "°")
        .under(pitchLock, Lock.FIXED);
    private final EnumSetting<Facing> facing = new EnumSetting<>("Place facing",
        "The way the server takes you to look whilst your view stays free. A block that turns by your"
            + " look is placed as if you looked that way.", Facing.OFF);
    private final KeybindSetting facingKey = new KeybindSetting("Facing key",
        "Looks that way whilst held. With no key set you look that way all the time.", KeybindSetting.UNBOUND)
        .under(facing, () -> !facing.is(Facing.OFF));

    public Rotation() {
        super("Rotation", "Locks your view to a chosen angle or places blocks as if you looked a chosen way.",
            Category.PLAYER);
        addSettings(yawLock, yaw, pitchLock, pitch, facing, facingKey);
        searchTags("yaw lock", "pitch lock", "look lock", "block rotation", "redstone placement");
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        apply();
    }

    private void apply() {
        if (!inGame()) {
            return;
        }
        switch (yawLock.getValue()) {
            case FIXED -> setYaw(yaw.getFloat());
            case SNAP -> setYaw(snap(mc.player.getYRot(), YAW_SNAP));
            case OFF -> { }
        }
        switch (pitchLock.getValue()) {
            case FIXED -> mc.player.setXRot(pitch.getFloat());
            case SNAP -> mc.player.setXRot(snap(mc.player.getXRot(), PITCH_SNAP));
            case OFF -> { }
        }
        faceServer();
    }

    // Straight up or down keeps the yaw of the view.
    private void faceServer() {
        Direction way = facing.getValue().way;
        if (way == null || facingKey.isBound() && !(facingKey.isHeld() && mc.gui.screen() == null)) {
            return;
        }
        float yaw = way.getAxis().isVertical() ? mc.player.getYRot() : way.toYRot();
        RotationManager.requestExact(yaw, -way.getStepY() * RotationManager.STRAIGHT_DOWN, RotationPriority.PLACE);
    }

    private void setYaw(float angle) {
        mc.player.setYRot(angle);
        mc.player.yHeadRot = angle;
        mc.player.yBodyRot = angle;
    }

    private static float snap(float angle, float step) {
        return Math.round(angle / step) * step;
    }
}
