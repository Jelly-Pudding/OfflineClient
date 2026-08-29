package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;

// Holds the view at a fixed angle. Snap rounds it to the nearest clean heading instead.
public final class Rotation extends Module {

    public enum Lock { OFF, FIXED, SNAP }

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

    public Rotation() {
        super("Rotation", "Locks your view to a chosen angle.", Category.PLAYER);
        addSettings(yawLock, yaw, pitchLock, pitch);
        searchTags("yaw lock", "pitch lock", "look lock");
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
