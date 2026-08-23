package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;

/**
 * CameraMixin swaps the look angles around the camera alignment and
 * MouseHandlerMixin sends the mouse here.
 */
public final class FreeLook extends Module {

    public enum Perspective {
        KEEP("Keep"),
        THIRD_PERSON("Third person"),
        FRONT("Front");

        private final String name;

        Perspective(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final BoolSetting hold = new BoolSetting("Hold",
        "Look around only whilst the bind is held down.", true);
    private final EnumSetting<Perspective> perspective = new EnumSetting<>("Perspective",
        "Camera to switch to whilst looking around.", Perspective.THIRD_PERSON);
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity",
        "Mouse speed whilst looking around.", 1, 0.1, 3, 0.1).min(0.05);

    private float yaw;
    private float pitch;
    private float savedYaw;
    private float savedPitch;
    private float savedYawO;
    private float savedPitchO;
    private CameraType savedCamera;
    // Null whilst nothing is swapped.
    private Entity swapped;
    private boolean primed;
    // A module lookup walks every registered module.
    private Freecam freecam;

    public FreeLook() {
        super("FreeLook", "Look around without turning your body.", Category.RENDER, GLFW.GLFW_KEY_LEFT_ALT);
        addSettings(hold, perspective, sensitivity);
        searchTags("free look", "perspective", "look behind");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        swapped = null;
        primed = mc.player != null;
        if (mc.player == null) {
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
        if (hold.isOn() && event.getAction() == GLFW.GLFW_RELEASE
            && getKeybind().isBound() && event.getKey() == getKeybind().getValue()) {
            setEnabled(false);
        }
    }

    // Mouse movement lands here instead of turning the player.
    public void turn(double deltaYaw, double deltaPitch) {
        // The 0.15 factor matches how the game turns the player.
        double factor = 0.15 * sensitivity.getValue();
        yaw += (float) (deltaYaw * factor);
        pitch = Mth.clamp(pitch + (float) (deltaPitch * factor), -90f, 90f);
    }

    public boolean isActive() {
        if (!isEnabled() || mc.player == null) {
            return false;
        }
        if (freecam == null) {
            freecam = OfflineClient.INSTANCE.getModuleManager().get(Freecam.class);
        }
        return !freecam.isEnabled();
    }

    // Applied for one camera alignment and then restored.
    public void applyRotation() {
        if (swapped != null || !isActive()) {
            return;
        }
        Entity entity = target();
        if (!primed) {
            // The module can be enabled before a player exists.
            yaw = entity.getYRot();
            pitch = entity.getXRot();
            primed = true;
        }
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
