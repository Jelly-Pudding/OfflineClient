package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.Dimension;

final class InDimension extends Condition {

    private final EnumSetting<Dimension> realm;

    InDimension(Frame frame) {
        super(frame, "Dimension");
        realm = add(new EnumSetting<>(frame.name("Dimension"), "You are in this dimension.", Dimension.NETHER));
    }

    @Override
    public boolean holds() {
        return realm.getValue().isHere();
    }

    @Override
    public String reason() {
        return "You were in " + realm.getValue().words() + ".";
    }
}
