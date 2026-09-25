package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.world.entity.Entity;

// CameraMixin swaps the look angles around the camera alignment.
// MouseHandlerMixin sends the mouse here.
public final class FreeLook extends Module {

    public enum Perspective { KEEP, THIRD_PERSON, FRONT }

    public enum Mode { PLAYER, CAMERA }

    private final BoolSetting hold = new BoolSetting("Hold",
        "Look around only whilst the bind is held down.", true);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What the mouse turns whilst the module is on.", Mode.PLAYER)
        .describe(Mode.PLAYER, "The mouse turns the camera and your body stays put.")
        .describe(Mode.CAMERA, "The camera freezes where it was and the mouse turns your body.");
    private final EnumSetting<Perspective> perspective = new EnumSetting<>("Perspective",
        "Camera to switch to whilst looking around.", Perspective.THIRD_PERSON)
        .describe(Perspective.KEEP, "Stays in whatever view you already had.")
        .describe(Perspective.THIRD_PERSON, "Switches to the view from behind.")
        .describe(Perspective.FRONT, "Switches to the view from the front.");
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity",
        "Mouse speed whilst looking around.", 1, 0.1, 3, 0.1).min(0.05);
    private final BoolSetting arrows = new BoolSetting("Arrow keys",
        "The arrow keys turn whichever of your body or the camera the mouse does not.", true);
    private final NumberSetting arrowSpeed = new NumberSetting("Arrow speed",
        "Degrees the arrow keys turn each tick.", 4, 1, 20, 0.5, " degrees").min(0.5)
        .under(arrows);

    private float yaw;
    private float pitch;
    private float savedYaw;
    private float savedPitch;
    private float savedYawO;
    private float savedPitchO;
    private CameraType savedCamera;
    // Null whilst nothing is swapped.
    private Entity swapped;

    public FreeLook() {
        super("FreeLook", "Look around without turning your body.", Category.RENDER);
        addSettings(hold, mode, perspective, sensitivity, arrows, arrowSpeed);
        searchTags("free look", "perspective", "look behind");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        swapped = null;
        if (mc.player == null) {
            setEnabled(false);
            return;
        }
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        savedCamera = mc.options.getCameraType();
        if (perspective.is(Perspective.THIRD_PERSON)) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (perspective.is(Perspective.FRONT)) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        }
    }

    @Override
    protected void onDisable() {
        restoreRotation();
        if (savedCamera != null) {
            mc.options.setCameraType(savedCamera);
            savedCamera = null;
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (hold.isOn() && event.getAction() == InputConstants.RELEASE
            && getKeybind().isBound() && event.getKey() == getKeybind().getValue()) {
            setEnabled(false);
        }
    }

    // Mouse movement lands here instead of turning the player.
    public void turn(double deltaYaw, double deltaPitch) {
        double factor = InputUtil.MOUSE_TURN * sensitivity.getValue();
        yaw += (float) (deltaYaw * factor);
        pitch = RotationManager.clampPitch(pitch + (float) (deltaPitch * factor));
    }

    // The arrow keys turn your body in player mode and the camera in camera mode.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!arrows.isOn() || !isActive() || mc.gui.screen() != null) {
            return;
        }
        float speed = arrowSpeed.getFloat();
        float step = speed * (axis(InputConstants.KEY_RIGHT) - axis(InputConstants.KEY_LEFT));
        float pitchStep = speed * (axis(InputConstants.KEY_DOWN) - axis(InputConstants.KEY_UP));
        if (step == 0 && pitchStep == 0) {
            return;
        }
        if (mode.is(Mode.PLAYER)) {
            mc.player.setYRot(mc.player.getYRot() + step);
            mc.player.setXRot(RotationManager.clampPitch(mc.player.getXRot() + pitchStep));
        } else {
            yaw += step;
            pitch = RotationManager.clampPitch(pitch + pitchStep);
        }
    }

    private static int axis(int key) {
        return InputConstants.isKeyDown(key) ? 1 : 0;
    }

    public boolean isActive() {
        if (!isEnabled() || mc.player == null) {
            return false;
        }
        return !Modules.enabled(Freecam.class);
    }

    // Only player mode takes the mouse away from your body.
    public boolean stealsMouse() {
        return isActive() && mode.is(Mode.PLAYER);
    }

    // Applied for one camera alignment and then restored.
    public void applyRotation() {
        if (swapped != null || !isActive()) {
            return;
        }
        Entity entity = target();
        savedYaw = entity.getYRot();
        savedPitch = entity.getXRot();
        savedYawO = entity.yRotO;
        savedPitchO = entity.xRotO;
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        swapped = entity;
    }

    public void restoreRotation() {
        if (swapped == null) {
            return;
        }
        Entity entity = swapped;
        swapped = null;
        entity.setYRot(savedYaw);
        entity.setXRot(savedPitch);
        entity.yRotO = savedYawO;
        entity.xRotO = savedPitchO;
    }

    private Entity target() {
        Entity entity = mc.getCameraEntity();
        return entity == null ? mc.player : entity;
    }
}
