package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.Modules;

import java.util.concurrent.ThreadLocalRandom;

// Sends silly rotations to the server whilst the local view is left alone.
public final class Derp extends Module {

    public enum Mode { SPIN, SHAKE, HEADBANG, RANDOM }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How your head moves to everyone else.", Mode.SPIN)
        .describe(Mode.SPIN, "Turns your head round and round.")
        .describe(Mode.SHAKE, "Shakes your head from side to side.")
        .describe(Mode.HEADBANG, "Nods your head up and down.")
        .describe(Mode.RANDOM, "Points your head somewhere new every tick.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast the head moves.", 30, 1, 90, 1, " degrees").min(1).max(180);
    private final BoolSetting pauseInCombat = new BoolSetting("Pause in combat",
        "Stops whilst your hands are busy or CrystalAura has a target.", true);

    private float yaw;
    private float pitch;
    private boolean rising = true;

    public Derp() {
        super("Derp", "Makes your head look ridiculous to everyone else.", Category.MISC);
        addSettings(mode, speed, pauseInCombat);
        searchTags("derp", "silly", "troll", "spin");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        if (inGame()) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (pauseInCombat.isOn() && aimingModuleActive()) {
            return;
        }

        float step = speed.getFloat();
        switch (mode.getValue()) {
            case SPIN -> {
                yaw += step;
                pitch = 0;
            }
            case SHAKE -> {
                yaw = mc.player.getYRot() + (yaw < mc.player.getYRot() ? step : -step);
                pitch = mc.player.getXRot();
            }
            case HEADBANG -> {
                yaw = mc.player.getYRot();
                pitch += rising ? step : -step;
                if (pitch >= 90) {
                    rising = false;
                } else if (pitch <= -90) {
                    rising = true;
                }
            }
            case RANDOM -> {
                yaw = ThreadLocalRandom.current().nextFloat(-180f, 180f);
                pitch = ThreadLocalRandom.current().nextFloat(-90f, 90f);
            }
        }
        yaw = wrap(yaw);
        pitch = Math.clamp(pitch, -90f, 90f);

        RotationManager.requestExact(yaw, pitch, RotationPriority.IDLE);
    }

    private boolean aimingModuleActive() {
        if (mc.player.swinging || mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            return true;
        }
        CrystalAura crystalAura = Modules.get(CrystalAura.class);
        return crystalAura != null && crystalAura.isEnabled() && crystalAura.hasTarget();
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        return wrapped > 180f ? wrapped - 360f : wrapped < -180f ? wrapped + 360f : wrapped;
    }
}
