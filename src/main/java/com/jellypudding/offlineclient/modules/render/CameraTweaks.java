package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.CameraType;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

// CameraMixin reads the distance and the wall setting from the third person distance hook.
public final class CameraTweaks extends Module {

    // Share of the current distance one wheel notch changes at sensitivity one.
    private static final double NOTCH_SHARE = 0.25;
    private static final double MIN_DISTANCE = 0.5;

    private final BoolSetting customDistance = new BoolSetting("Custom distance",
        "Uses your own third person distance instead of the vanilla four blocks.", true);
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far behind you the third person camera sits.", 10, 1, 64, 0.5, " blocks")
        .max(150).under(customDistance);
    private final BoolSetting scrolling = new BoolSetting("Scrolling",
        "The mouse wheel moves the camera in and out whilst in third person.", true)
        .under(customDistance);
    private final KeybindSetting scrollKey = new KeybindSetting("Scroll key",
        "A key that must be held for the wheel to move the camera. Unbound means always.", GLFW.GLFW_KEY_LEFT_ALT)
        .under(scrolling);
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity",
        "How much of the distance one wheel notch adds or takes away.", 1, 0.1, 3, 0.1, "x").min(0.01)
        .under(scrolling);
    private final BoolSetting noClip = new BoolSetting("No clip",
        "Lets the camera pass through walls instead of snapping back to your head.", true);

    // The distance after scrolling. Goes back to the setting when the view changes.
    private double scrolled;
    private double lastSetting;
    private CameraType lastView;

    public CameraTweaks() {
        super("CameraTweaks", "Pulls the third person camera further out and through walls.", Category.RENDER);
        addSettings(customDistance, distance, scrolling, scrollKey, sensitivity, noClip);
        searchTags("camera distance", "camera no clip", "third person", "perspective");
    }

    @Override
    public String getSuffix() {
        if (!customDistance.isOn()) {
            return null;
        }
        return String.format(Locale.ROOT, "%.1f", current());
    }

    @Override
    protected void onEnable() {
        reset();
        lastView = mc.options.getCameraType();
    }

    private void reset() {
        scrolled = distance.getValue();
        lastSetting = scrolled;
    }

    // A new view or a slider change throws the scrolled distance away.
    @Subscribe
    private void onTick(TickEvent event) {
        CameraType view = mc.options.getCameraType();
        if (view != lastView || distance.getValue() != lastSetting) {
            lastView = view;
            reset();
        }
    }

    @Subscribe
    private void onScroll(MouseScrollEvent event) {
        if (!customDistance.isOn() || !scrolling.isOn() || mc.gui.screen() != null
            || mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            return;
        }
        if (scrollKey.isBound() && !scrollKey.isHeld()) {
            return;
        }
        double before = current();
        scrolled = Math.max(MIN_DISTANCE, before - event.getAmount() * NOTCH_SHARE * sensitivity.getValue() * before);
        event.cancel();
    }

    private double current() {
        if (scrolled <= 0) {
            reset();
        }
        return scrolled;
    }

    // Vanilla passes in the distance it wants before the wall check shortens it.
    public float adjustDistance(float vanilla) {
        if (!isEnabled() || !customDistance.isOn()) {
            return vanilla;
        }
        return (float) current();
    }

    public boolean passesThroughWalls() {
        return isEnabled() && noClip.isOn();
    }
}
