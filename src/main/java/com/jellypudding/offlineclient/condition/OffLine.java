package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// Drifting sideways off a straight line such as a highway. The line starts where you stand as
// the watching begins and runs the way you face rounded to the nearest eighth turn.
final class OffLine extends Condition {

    private static final float EIGHTH_TURN = 45;

    private final NumberSetting drift;

    // A point on the line and the unit vector along it. Null until the line is taken.
    private Vec3 origin;
    private Vec3 along;
    private double off;

    OffLine(Frame frame) {
        super(frame, "Off the line");
        drift = add(new NumberSetting(frame.name("Off the line"),
            "You are more than this many blocks to the side of the line you set out on. The line starts where"
                + " you stand as the module goes on and runs the way you face rounded to the nearest eighth turn.",
            3, 0.5, 16, 0.5, " blocks").min(0));
        add(fromHere(frame.name("Take the line"),
            "Starts the line again from where you stand along the way you face.", () -> {
                take();
                return "Line taken";
            }));
    }

    private void take() {
        double yaw = Math.toRadians(Math.round(mc.player.getYRot() / EIGHTH_TURN) * EIGHTH_TURN);
        origin = mc.player.position();
        along = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    // The line is taken on the next tick in a world.
    @Override
    public void start() {
        origin = null;
    }

    @Override
    public boolean holds() {
        if (origin == null) {
            take();
        }
        Vec3 from = mc.player.position().subtract(origin);
        off = Math.abs(from.x * along.z - from.z * along.x);
        return off > drift.getValue();
    }

    @Override
    public String reason() {
        return "You were " + String.format(Locale.ROOT, "%.1f", off) + " blocks off the line.";
    }
}
