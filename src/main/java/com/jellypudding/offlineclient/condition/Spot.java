package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.world.phys.Vec3;

// Being within a radius of typed coordinates. Two numbers are a column and any height counts.
// Three numbers pin the height as well.
final class Spot extends Condition {

    private static final int COLUMN = 2;
    private static final int POINT = 3;

    private final TextSetting place;
    private final TypedNumbers typed;
    private final NumberSetting radius;
    private final EnumSetting<Within> within;

    Spot(Frame frame) {
        super(frame, "At a place");
        place = add(new TextSetting(frame.name("At a place"),
            "You are close to this place. Click to type it as x z for any height or as x y z.", "").secret());
        typed = new TypedNumbers(place, COLUMN, POINT);
        radius = add(new NumberSetting(frame.name("Place radius"),
            "How close to the place you have to be.", 16, 1, 256, 1, " blocks").min(0));
        within = add(Within.row(frame.name("Place dimension")));
        add(fromHere(frame.name("Place here"),
            "Writes where you stand and the dimension you are in into the place.", () -> writeHere(place, within)));
    }

    @Override
    public boolean holds() {
        double[] at = typed.get();
        if (at == null || !within.getValue().isHere()) {
            return false;
        }
        Vec3 self = mc.player.position();
        double x = self.x - at[0];
        double z = self.z - at[at.length - 1];
        double y = at.length == POINT ? self.y - at[1] : 0;
        return Math.sqrt(x * x + y * y + z * z) <= radius.getValue();
    }

    @Override
    public String reason() {
        return "You were within " + radius.getValueString() + " of " + place.getValue().trim() + ".";
    }

    @Override
    public String problem() {
        return typed.get() == null ? "At a place needs its coordinates typed as x z or as x y z." : null;
    }
}
