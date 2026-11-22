package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;

// On whilst the entry is picked. It keeps nothing itself and the list saves the pick.
final class Picked extends BoolSetting {

    private final ChoiceListSetting list;
    private final String entry;

    Picked(String name, String description, ChoiceListSetting list, String entry) {
        super(name, description, false);
        this.list = list;
        this.entry = entry;
        noDefault();
    }

    @Override
    public boolean isOn() {
        return list.contains(entry);
    }

    @Override
    public Boolean getValue() {
        return isOn();
    }

    @Override
    public void setValue(Boolean on) {
        if (on) {
            list.add(entry);
        } else {
            list.remove(entry);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean saves() {
        return false;
    }
}
