package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;

// CameraMixin and MouseHandlerMixin apply the zoom at read time. The saved options are never touched.
public final class Zoom extends Module {

    private static final double EASE = 0.3;

    private static final double SETTLED = 0.005;

    private static final double NOTCH_SHARE = 0.25;

    private final NumberSetting factor = new NumberSetting("Factor",
        "How far to zoom in.", 4, 2, 10, 0.5, "x").min(1);
    private final NumberSetting scrollSensitivity = new NumberSetting("Scroll sensitivity",
        "How much a wheel notch changes the zoom. Zero leaves the wheel to the hotbar.",
        1, 0, 5, 0.1);
    private final BoolSetting smooth = new BoolSetting("Smooth",
        "Eases into the zoom instead of snapping to it.", true);
    private final BoolSetting cinematic = new BoolSetting("Cinematic",
        "Uses the slow cinematic camera whilst zoomed.", false);
    private final BoolSetting hideHud = new BoolSetting("Hide HUD",
        "Hides the game overlay whilst zoomed.", false);
    private final BoolSetting showHands = new BoolSetting("Show hands",
        "Keeps drawing your hands whilst zoomed.", false);

    private double smoothed = 1;

    private boolean savedCinematic;
    private boolean hidHud;

    public Zoom() {
        super("Zoom", "Zooms your view.", Category.RENDER);
        addSettings(factor, scrollSensitivity, smooth, cinematic, hideHud, showHands);
        holdByDefault();
    }

    @Override
    public String getSuffix() {
        return factor.getValueString();
    }

    // Starting the game zoomed in helps nobody.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        savedCinematic = mc.options.smoothCamera;
        hidHud = hideHud.isOn() && !mc.gui.hud.isHidden();
        if (hidHud) {
            mc.gui.hud.toggle();
        }
        apply();
    }

    @Override
    protected void onDisable() {
        mc.options.smoothCamera = savedCinematic;
        // Left alone if the player has hidden or shown the HUD since.
        if (hidHud && mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        hidHud = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        apply();
    }

    @Subscribe
    private void onScroll(MouseScrollEvent event) {
        double strength = scrollSensitivity.getValue();
        if (strength <= 0 || mc.gui.screen() != null) {
            return;
        }
        double current = factor.getValue();
        factor.setValue(current + event.getAmount() * NOTCH_SHARE * strength * current);
        event.cancel();
    }

    private void apply() {
        mc.options.smoothCamera = savedCinematic || cinematic.isOn();
    }

    // A steadier aim the further in you are. The cinematic camera does that on its own.
    public double scaleSensitivity(double sensitivity) {
        return cinematic.isOn() ? sensitivity : sensitivity / Math.max(1, factor.getValue() / 2);
    }

    // Eases the zoom once per camera update. The world and hand read the same value.
    public void advance() {
        double target = isEnabled() ? factor.getValue() : 1;
        if (!smooth.isOn()) {
            smoothed = target;
            return;
        }
        smoothed += (target - smoothed) * EASE;
        if (Math.abs(smoothed - target) < SETTLED) {
            smoothed = target;
        }
    }

    public boolean hidesHand() {
        return isEnabled() && !showHands.isOn();
    }

    public float applyZoom(float fov) {
        return (float) (fov / smoothed);
    }
}
