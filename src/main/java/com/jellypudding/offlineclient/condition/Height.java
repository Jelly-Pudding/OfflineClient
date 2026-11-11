package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;

// Being below or above a level such as sinking under a highway.
final class Height extends Condition {

    enum Side { BELOW, ABOVE }

    // No world reaches lower.
    private static final double LOWEST = -2048;

    private final EnumSetting<Side> side;
    private final NumberSetting level;
    private final EnumSetting<Within> within;

    Height(Frame frame) {
        super(frame, "Height");
        side = add(new EnumSetting<>(frame.name("Height"),
            "Which side of a level your feet are on.", Side.BELOW)
            .describe(Side.BELOW, "Your feet are lower than the level.")
            .describe(Side.ABOVE, "Your feet are higher than the level."));
        level = add(new NumberSetting(frame.name("Height level"),
            "The Y level your feet are held against.", 60, -64, 320, 1).min(LOWEST));
        within = add(Within.row(frame.name("Height dimension")));
    }

    @Override
    public boolean holds() {
        double feet = mc.player.getY();
        return within.getValue().isHere()
            && (side.is(Side.BELOW) ? feet < level.getValue() : feet > level.getValue());
    }

    @Override
    public String reason() {
        return "You were " + (side.is(Side.BELOW) ? "below" : "above") + " Y " + level.getValueString() + ".";
    }
}
