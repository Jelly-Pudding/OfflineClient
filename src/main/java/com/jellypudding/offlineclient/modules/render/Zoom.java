package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/**
 * The FOV change happens in CameraMixin at read time. The saved video
 * settings are never touched.
 */
public final class Zoom extends Module {

    private final NumberSetting factor = new NumberSetting("Factor",
        "How far to zoom in.", 4, 2, 10, 0.5, "x").min(1);

    private double smoothed = 1;

    public Zoom() {
        super("Zoom", "Zooms your view.", Category.RENDER, GLFW.GLFW_KEY_C);
        addSettings(factor);
    }

    @Override
    public String getSuffix() {
        return factor.getValueString();
    }

    // Called every frame.
    public float applyZoom(float fov) {
        double target = isEnabled() ? factor.getValue() : 1;
        smoothed += (target - smoothed) * 0.3;
        if (Math.abs(smoothed - target) < 0.005) {
            smoothed = target;
        }
        return (float) (fov / smoothed);
    }
}
