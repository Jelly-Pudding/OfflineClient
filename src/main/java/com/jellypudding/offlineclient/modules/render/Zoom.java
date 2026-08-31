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

    // Share of the remaining distance the ease covers each frame.
    private static final double EASE = 0.3;

    // Below this the ease has arrived.
    private static final double SETTLED = 0.005;

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

    /**
     * Eases the zoom on once a camera update. The world and the hand each read
     * the field of view separately and both have to see the same value.
     */
    public void advance() {
        double target = isEnabled() ? factor.getValue() : 1;
        smoothed += (target - smoothed) * EASE;
        if (Math.abs(smoothed - target) < SETTLED) {
            smoothed = target;
        }
    }

    public float applyZoom(float fov) {
        return (float) (fov / smoothed);
    }
}
