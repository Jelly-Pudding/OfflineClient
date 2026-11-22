package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Totems;

// Enough totems in the bag can take the place of the health.
final class Health extends Condition {

    private final NumberSetting line;
    private final NumberSetting fightOn;
    private final BoolSetting predict;
    private String reason = "";

    Health(Frame frame) {
        super(frame, "Health");
        line = add(new NumberSetting(frame.name("Health"),
            "Your health is at or below this many hearts. Absorption hearts count as health.",
            3, 0.5, 20, 0.5, " hearts"));
        fightOn = add(new NumberSetting(frame.name("Fight on with"),
            "Low health does not count whilst you carry at least this many totems. Zero turns it off.",
            0, 0, 10, 1, " totems"));
        predict = add(new BoolSetting(frame.name("Predict damage"),
            "Also counts when a crystal or bed or weapon or fall nearby would take you down to that.", true));
    }

    @Override
    public boolean holds() {
        if (fightOn.getInt() > 0 && Totems.carried() >= fightOn.getInt()) {
            return false;
        }
        if (EntityUtil.healthAtOrBelow(line.getValue())) {
            reason = "Health was at or below " + line.getValueString() + ".";
            return true;
        }
        if (predict.isOn() && DamageUtil.healthAfterIncoming() <= line.getFloat() * EntityUtil.HEART) {
            reason = "Something nearby was about to take you to " + line.getValueString() + ".";
            return true;
        }
        return false;
    }

    @Override
    public String reason() {
        return reason;
    }
}
