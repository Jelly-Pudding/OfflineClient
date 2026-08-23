package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;

// CameraMixin reads both settings from the third person distance hook.
public final class CameraTweaks extends Module {

    private final BoolSetting customDistance = new BoolSetting("Custom distance",
        "Uses your own third person distance instead of the vanilla four blocks.", true);
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far behind you the third person camera sits.", 10, 1, 64, 0.5, " blocks")
        .visibleWhen(customDistance::isOn);
    private final BoolSetting noClip = new BoolSetting("No clip",
        "Lets the camera pass through walls instead of snapping back to your head.", true);

    public CameraTweaks() {
        super("CameraTweaks", "Pulls the third person camera further out and through walls.", Category.RENDER);
        addSettings(customDistance, distance, noClip);
        searchTags("camera distance", "camera no clip", "third person", "perspective");
    }

    @Override
    public String getSuffix() {
        return customDistance.isOn() ? distance.getValueString() : null;
    }

    // Vanilla passes in the distance it wants before the wall check shortens it.
    public float adjustDistance(float vanilla) {
        if (!isEnabled() || !customDistance.isOn()) {
            return vanilla;
        }
        return distance.getFloat();
    }

    public boolean passesThroughWalls() {
        return isEnabled() && noClip.isOn();
    }
}
